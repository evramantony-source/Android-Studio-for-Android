package com.example.ui.editor

import com.example.model.FileLanguage

enum class SymbolKind(val badge: String) {
    CLASS("C"),
    INTERFACE("I"),
    ENUM("E"),
    METHOD("fn"),
    FIELD("v"),
    XML_ID("#id"),
    XML_TAG("<>"),
}

data class CodeSymbol(
    val name: String,
    val detail: String,
    val line: Int,
    val kind: SymbolKind,
)

object CodeStructureExtractor {

    private val JVM_TYPE_REGEX = Regex(
        """^\s*(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|enum|static|annotation)\s+)*(class|interface|object|enum\s+class|enum|record)\s+([A-Za-z_]\w*)""",
    )
    private val KOTLIN_FUN_REGEX = Regex(
        """^\s*(?:(?:public|private|protected|internal|override|suspend|inline|open|final)\s+)*fun\s+(?:<[^>]+>\s*)?([A-Za-z_]\w*)\s*\(([^)]*)\)""",
    )
    private val JAVA_METHOD_REGEX = Regex(
        """^\s*(?:(?:public|private|protected|static|final|synchronized|abstract|native)\s+)+(?:<[^>]+>\s+)?([A-Za-z_][\w<>\[\], ?]*)\s+([A-Za-z_]\w*)\s*\(([^)]*)\)\s*(?:throws\s+[A-Za-z0-9_., ]+)?(?:\{|;)?\s*$""",
    )
    private val KOTLIN_PROP_REGEX = Regex(
        """^\s*(?:(?:private|protected|internal|public|override|const|lateinit)\s+)*(val|var)\s+([A-Za-z_]\w*)(?:\s*:\s*([^=]+))?""",
    )
    private val JAVA_FIELD_REGEX = Regex(
        """^\s*(?:(?:private|protected|public|static|final|volatile|transient)\s+)+([A-Za-z_][\w<>\[\], ?]*)\s+([A-Za-z_]\w*)\s*(?:=|;)""",
    )
    private val XML_ID_REGEX = Regex("""android:id\s*=\s*"@\+?id/([^"]+)"""")
    private val XML_TAG_REGEX = Regex("""^\s*<([A-Za-z0-9_.:-]+)(?:\s|>|/>)""")
    private val JSON_KEY_REGEX = Regex("""^\s*"([^"]+)"\s*:""")

    fun extract(content: String, language: FileLanguage): List<CodeSymbol> {
        if (content.isBlank()) return emptyList()
        val symbols = mutableListOf<CodeSymbol>()
        val lines = content.lines()
        lines.forEachIndexed { idx, rawLine ->
            val lineNum = idx + 1
            val line = rawLine.trimEnd()
            if (line.isBlank()) return@forEachIndexed
            when (language) {
                FileLanguage.KOTLIN, FileLanguage.JAVA -> {
                    val typeMatch = JVM_TYPE_REGEX.find(line)
                    if (typeMatch != null) {
                        val kw = typeMatch.groupValues[1]
                        val name = typeMatch.groupValues[2]
                        val kind = when {
                            kw.contains("interface") -> SymbolKind.INTERFACE
                            kw.contains("enum") -> SymbolKind.ENUM
                            else -> SymbolKind.CLASS
                        }
                        symbols.add(CodeSymbol(name = name, detail = kw, line = lineNum, kind = kind))
                        return@forEachIndexed
                    }
                    if (language == FileLanguage.KOTLIN) {
                        val funMatch = KOTLIN_FUN_REGEX.find(line)
                        if (funMatch != null) {
                            val name = funMatch.groupValues[1]
                            val params = funMatch.groupValues[2].trim()
                            symbols.add(CodeSymbol(name = "$name($params)", detail = "fun", line = lineNum, kind = SymbolKind.METHOD))
                            return@forEachIndexed
                        }
                        val propMatch = KOTLIN_PROP_REGEX.find(line)
                        if (propMatch != null && !rawLine.startsWith("        ")) {
                            symbols.add(
                                CodeSymbol(
                                    name = propMatch.groupValues[2],
                                    detail = propMatch.groupValues[1],
                                    line = lineNum,
                                    kind = SymbolKind.FIELD,
                                ),
                            )
                        }
                    } else {
                        val methodMatch = JAVA_METHOD_REGEX.find(line)
                        if (methodMatch != null) {
                            val ret = methodMatch.groupValues[1].trim()
                            val name = methodMatch.groupValues[2]
                            val params = methodMatch.groupValues[3].trim()
                            if (ret !in setOf("return", "throw", "new", "else", "if", "for", "while", "switch")) {
                                symbols.add(CodeSymbol(name = "$name($params)", detail = ret, line = lineNum, kind = SymbolKind.METHOD))
                                return@forEachIndexed
                            }
                        }
                        val fieldMatch = JAVA_FIELD_REGEX.find(line)
                        if (fieldMatch != null) {
                            symbols.add(
                                CodeSymbol(
                                    name = fieldMatch.groupValues[2],
                                    detail = fieldMatch.groupValues[1].trim(),
                                    line = lineNum,
                                    kind = SymbolKind.FIELD,
                                ),
                            )
                        }
                    }
                }

                FileLanguage.XML -> {
                    val idMatch = XML_ID_REGEX.find(line)
                    if (idMatch != null) {
                        symbols.add(
                            CodeSymbol(
                                name = "@+id/${idMatch.groupValues[1]}",
                                detail = "View ID",
                                line = lineNum,
                                kind = SymbolKind.XML_ID,
                            ),
                        )
                    } else {
                        val tagMatch = XML_TAG_REGEX.find(line)
                        if (tagMatch != null && !line.trimStart().startsWith("</") && !line.trimStart().startsWith("<?")) {
                            val tag = tagMatch.groupValues[1]
                            if (tag in setOf("manifest", "application", "activity", "service", "receiver", "uses-permission", "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout", "resources")) {
                                symbols.add(CodeSymbol(name = "<$tag>", detail = "XML", line = lineNum, kind = SymbolKind.XML_TAG))
                            }
                        }
                    }
                }

                FileLanguage.JSON -> {
                    val keyMatch = JSON_KEY_REGEX.find(line)
                    if (keyMatch != null && rawLine.takeWhile { it == ' ' }.length <= 4) {
                        symbols.add(
                            CodeSymbol(
                                name = keyMatch.groupValues[1],
                                detail = "key",
                                line = lineNum,
                                kind = SymbolKind.FIELD,
                            ),
                        )
                    }
                }

                FileLanguage.TEXT -> Unit
            }
        }
        return symbols
    }

    fun activeSymbolBreadcrumb(symbols: List<CodeSymbol>, currentLine: Int): String? {
        if (symbols.isEmpty()) return null
        val enclosingClass = symbols.lastOrNull {
            (it.kind == SymbolKind.CLASS || it.kind == SymbolKind.INTERFACE || it.kind == SymbolKind.ENUM) &&
                it.line <= currentLine
        }
        val enclosingMember = symbols.lastOrNull {
            it.kind == SymbolKind.METHOD && it.line <= currentLine
        }
        return when {
            enclosingClass != null && enclosingMember != null && enclosingMember.line >= enclosingClass.line ->
                "${enclosingClass.name} › ${enclosingMember.name.substringBefore('(')}()"
            enclosingClass != null -> enclosingClass.name
            enclosingMember != null -> "${enclosingMember.name.substringBefore('(')}()"
            else -> null
        }
    }
}
