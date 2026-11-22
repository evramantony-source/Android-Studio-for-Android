package com.example.ui.editor

import android.util.LruCache
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.model.FileLanguage
import com.example.ui.theme.SyntaxColors

class CodeSyntaxVisualTransformation(
    private val language: FileLanguage,
    private val colors: SyntaxColors,
    private val filePath: String = "",
) : VisualTransformation {
    private var lastSource: String? = null
    private var lastHighlighted: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val cached = if (raw === lastSource) lastHighlighted else null
        val highlighted = cached ?: SyntaxHighlighter.highlight(raw, language, colors, filePath).also {
            lastSource = raw
            lastHighlighted = it
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

object SyntaxHighlighter {

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null",
    )

    private val JAVA_COMMON_TYPES = setOf(
        "String", "Object", "Integer", "Boolean", "Double", "Float", "Long", "Math",
        "System", "Activity", "Bundle", "Context", "Intent", "View", "ViewGroup",
        "LinearLayout", "FrameLayout", "ScrollView", "GridLayout", "RelativeLayout",
        "TextView", "Button", "EditText", "ImageView", "CheckBox", "Switch", "SeekBar",
        "ListView", "ArrayAdapter", "Toast", "AlertDialog", "Canvas", "Paint", "Path",
        "Color", "Typeface", "GradientDrawable", "MotionEvent", "Gravity", "Log",
        "WebView", "SharedPreferences", "Handler", "Looper", "ArrayList", "List", "Map",
        "HashMap", "Override", "R",
    )

    private val KOTLIN_KEYWORDS = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
        "companion", "const", "constructor", "continue", "crossinline", "data",
        "delegate", "do", "dynamic", "else", "enum", "expect", "external", "false",
        "field", "file", "final", "finally", "for", "fun", "get", "if", "import",
        "in", "infix", "init", "inline", "inner", "interface", "internal", "is",
        "lateinit", "noinline", "null", "object", "open", "operator", "out", "override",
        "package", "param", "private", "property", "protected", "public", "reified",
        "return", "sealed", "set", "setparam", "super", "suspend", "tailrec", "this",
        "throw", "true", "try", "typealias", "typeof", "val", "var", "vararg",
        "when", "where", "while",
    )

    private val GRADLE_KEYWORDS = KOTLIN_KEYWORDS + setOf(
        "plugins", "android", "dependencies", "implementation", "api", "testImplementation",
        "androidTestImplementation", "ksp", "id", "version", "apply", "namespace",
        "compileSdk", "minSdk", "targetSdk", "defaultConfig", "buildTypes", "release",
        "debug", "sourceSets", "repositories", "google", "mavenCentral", "include",
    )

    private val KOTLIN_COMMON_TYPES = JAVA_COMMON_TYPES + setOf(
        "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "Unit", "Nothing", "Any", "IntArray", "FloatArray", "BooleanArray", "Array",
        "MutableList", "MutableMap", "Pair", "Regex",
    )

    private const val MAX_HIGHLIGHT_CHARS = 35_000
    private const val MAX_CACHE_CODE_CHARS = 65_000

    private data class CachedHighlight(
        val source: String,
        val colors: SyntaxColors,
        val language: FileLanguage,
        val filePath: String,
        val result: AnnotatedString,
    )

    private val highlightCache = object : LruCache<String, CachedHighlight>(28) {}

    fun highlight(
        code: String,
        language: FileLanguage,
        colors: SyntaxColors,
        filePath: String = "",
    ): AnnotatedString {
        if (code.isEmpty()) return AnnotatedString(code)
        val lowerPath = filePath.lowercase()
        val isGradle = lowerPath.endsWith(".gradle") || lowerPath.endsWith(".gradle.kts") || lowerPath.endsWith(".kts")
        val isYaml = lowerPath.endsWith(".yml") || lowerPath.endsWith(".yaml") || lowerPath.endsWith(".toml") || lowerPath.endsWith(".properties")
        val isMarkdown = lowerPath.endsWith(".md") || lowerPath.endsWith(".markdown")
        if (language == FileLanguage.TEXT && !isGradle && !isYaml && !isMarkdown) {
            return AnnotatedString(code)
        }
        val cacheKey = if (code.length <= MAX_CACHE_CODE_CHARS) {
            "${language.name}|${colors.hashCode()}|$lowerPath|${code.hashCode()}|${code.length}"
        } else {
            null
        }
        if (cacheKey != null) {
            val cached = synchronized(highlightCache) { highlightCache.get(cacheKey) }
            if (cached != null && cached.language == language && cached.colors == colors && cached.filePath == lowerPath && cached.source == code) {
                return cached.result
            }
        }
        val clipped = if (code.length > MAX_HIGHLIGHT_CHARS) code.substring(0, MAX_HIGHLIGHT_CHARS) else code
        val styledHead = when {
            isGradle -> highlightJvmLanguage(clipped, colors, GRADLE_KEYWORDS, KOTLIN_COMMON_TYPES)
            isYaml -> highlightYamlOrToml(clipped, colors)
            isMarkdown -> highlightMarkdown(clipped, colors)
            language == FileLanguage.JAVA -> highlightJvmLanguage(clipped, colors, JAVA_KEYWORDS, JAVA_COMMON_TYPES)
            language == FileLanguage.KOTLIN -> highlightJvmLanguage(clipped, colors, KOTLIN_KEYWORDS, KOTLIN_COMMON_TYPES)
            language == FileLanguage.XML -> highlightXml(clipped, colors)
            language == FileLanguage.JSON -> highlightJson(clipped, colors)
            else -> AnnotatedString(clipped)
        }
        val result = if (code.length <= MAX_HIGHLIGHT_CHARS) {
            styledHead
        } else {
            buildAnnotatedString {
                append(styledHead)
                append(code.substring(MAX_HIGHLIGHT_CHARS))
            }
        }
        if (cacheKey != null) {
            synchronized(highlightCache) {
                highlightCache.put(
                    cacheKey,
                    CachedHighlight(
                        source = code,
                        colors = colors,
                        language = language,
                        filePath = lowerPath,
                        result = result,
                    ),
                )
            }
        }
        return result
    }

    private fun highlightYamlOrToml(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            var offset = 0
            code.lines().forEach { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("#")) {
                    addStyle(SpanStyle(color = colors.comment), offset, offset + line.length)
                } else {
                    val colon = line.indexOfFirst { it == ':' || it == '=' }
                    if (colon > 0) {
                        addStyle(SpanStyle(color = colors.xmlAttr, fontWeight = FontWeight.Medium), offset, offset + colon)
                    }
                    val firstQuote = line.indexOf('"')
                    if (firstQuote >= 0) {
                        addStyle(SpanStyle(color = colors.string), offset + firstQuote, offset + line.length)
                    }
                }
                offset += line.length + 1
            }
        }

    private fun highlightMarkdown(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            var offset = 0
            code.lines().forEach { line ->
                val trimmed = line.trimStart()
                when {
                    trimmed.startsWith("#") ->
                        addStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold), offset, offset + line.length)
                    trimmed.startsWith("```") || trimmed.startsWith(">") ->
                        addStyle(SpanStyle(color = colors.comment), offset, offset + line.length)
                    trimmed.startsWith("-") || trimmed.startsWith("*") ->
                        addStyle(SpanStyle(color = colors.xmlTag), offset, (offset + 2).coerceAtMost(offset + line.length))
                }
                offset += line.length + 1
            }
        }

    private fun highlightJvmLanguage(
        code: String,
        colors: SyntaxColors,
        keywords: Set<String>,
        commonTypes: Set<String>,
    ): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    // Single-line comment
                    c == '/' && i + 1 < len && code[i + 1] == '/' -> {
                        val end = code.indexOf('\n', i).let { if (it == -1) len else it }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    // Multi-line comment
                    c == '/' && i + 1 < len && code[i + 1] == '*' -> {
                        val end = code.indexOf("*/", i + 2).let { if (it == -1) len else it + 2 }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    // String or char literal
                    c == '"' || c == '\'' -> {
                        val quote = c
                        var j = i + 1
                        while (j < len) {
                            if (code[j] == '\\' && j + 1 < len) {
                                j += 2
                            } else if (code[j] == quote) {
                                j++
                                break
                            } else if (code[j] == '\n') {
                                break
                            } else {
                                j++
                            }
                        }
                        addStyle(SpanStyle(color = colors.string), i, j)
                        i = j
                    }
                    // Annotation
                    c == '@' -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        addStyle(SpanStyle(color = colors.annotation), i, j)
                        i = j
                    }
                    // Number
                    c.isDigit() -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                        addStyle(SpanStyle(color = colors.number), i, j)
                        i = j
                    }
                    // Identifier / Keyword / Type
                    c.isLetter() || c == '_' -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                        val word = code.substring(i, j)
                        when {
                            word in keywords -> addStyle(
                                SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium),
                                i,
                                j,
                            )
                            word in commonTypes || (word[0].isUpperCase() && word.any { it.isLowerCase() }) ->
                                addStyle(SpanStyle(color = colors.type), i, j)
                        }
                        i = j
                    }
                    else -> i++
                }
            }
        }

    private fun highlightXml(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    code.startsWith("<!--", i) -> {
                        val end = code.indexOf("-->", i + 4).let { if (it == -1) len else it + 3 }
                        addStyle(SpanStyle(color = colors.comment), i, end)
                        i = end
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < len && code[j] != '"' && code[j] != '\n') j++
                        if (j < len && code[j] == '"') j++
                        addStyle(SpanStyle(color = colors.string), i, j)
                        i = j
                    }
                    c == '<' -> {
                        var j = i + 1
                        if (j < len && (code[j] == '/' || code[j] == '?')) j++
                        while (j < len && !code[j].isWhitespace() && code[j] != '>' && code[j] != '/') j++
                        addStyle(SpanStyle(color = colors.xmlTag, fontWeight = FontWeight.Medium), i, j)
                        i = j
                    }
                    c == '>' || (c == '/' && i + 1 < len && code[i + 1] == '>') -> {
                        val end = if (c == '/') i + 2 else i + 1
                        addStyle(SpanStyle(color = colors.xmlTag), i, end)
                        i = end
                    }
                    (c.isLetter() || c == '_') -> {
                        var j = i + 1
                        while (j < len && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == ':')) j++
                        if (j < len && code[j] == '=') {
                            addStyle(SpanStyle(color = colors.xmlAttr), i, j)
                        }
                        i = j
                    }
                    else -> i++
                }
            }
        }

    private fun highlightJson(code: String, colors: SyntaxColors): AnnotatedString =
        buildAnnotatedString {
            append(code)
            val len = code.length
            var i = 0
            while (i < len) {
                val c = code[i]
                when {
                    c == '"' -> {
                        var j = i + 1
                        while (j < len && code[j] != '"' && code[j] != '\n') {
                            if (code[j] == '\\' && j + 1 < len) j += 2 else j++
                        }
                        if (j < len && code[j] == '"') j++
                        var look = j
                        while (look < len && code[look].isWhitespace()) look++
                        val isKey = look < len && code[look] == ':'
                        addStyle(
                            SpanStyle(color = if (isKey) colors.xmlAttr else colors.string),
                            i,
                            j,
                        )
                        i = j
                    }
                    c.isDigit() -> {
                        var j = i + 1
                        while (j < len && (code[j].isDigit() || code[j] == '.')) j++
                        addStyle(SpanStyle(color = colors.number), i, j)
                        i = j
                    }
                    else -> i++
                }
            }
        }
}
