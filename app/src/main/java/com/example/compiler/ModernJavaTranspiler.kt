package com.example.compiler

/**
 * Lowers modern Java (11, 17, 21, 25) syntax features down to Java 8-compatible source
 * so the bundled Eclipse JDT compiler + Dalvik DX can compile modern Java code offline:
 * - Java 25 `IO.println(...)` / `IO.print(...)` -> `System.out.println(...)` / `System.out.print(...)`
 * - Java 21-25 unnamed variables/lambda params `_` -> unique `_unusedN` identifiers
 * - Java 15/17 text blocks `"""..."""` -> standard concatenated string literals (preserving line counts)
 * - Java 16/17 `record Foo(Type a, Type b)` declarations -> immutable Java 8 classes with getters, equals, hashCode, toString
 * - Java 16+ pattern-matching `if (obj instanceof Foo f)` -> `if (obj instanceof Foo) { Foo f = (Foo) obj; ... }`
 * - Java 14+ switch arrow cases `case A, B -> expr;` / `val = switch (x) { ... }` -> Java 8 switch statements
 * - Java 10+ local `var name = expr;` -> inferred Java types
 * - Java 9/11 helpers: `List.of(...)`, `Set.of(...)`, `Map.of(...)`, `.isBlank()`, `.repeat(n)`, `.lines()`, `.formatted(...)`
 */
object ModernJavaTranspiler {

    data class TranspileResult(
        val java8Source: String,
        val lineMap: Map<Int, Int>,
        val rewrittenFeatures: List<String>,
    )

    fun transpileToJava8(source: String): TranspileResult {
        val features = linkedSetOf<String>()
        var current = source.replace("\r\n", "\n")

        // 0. Wrap Java 21-25 compact top-level `void main()` if no type declaration exists
        val compactMainResult = wrapCompactTopLevelMain(current)
        if (compactMainResult.second) features.add("compact main")
        current = compactMainResult.first

        // 0b. Strip `sealed` / `non-sealed` modifiers and `permits ...` clauses (Java 17+)
        val sealedResult = stripSealedAndPermits(current)
        if (sealedResult.second) features.add("sealed classes")
        current = sealedResult.first

        // 1. Expand Java 15+ text blocks """ ... """
        val textBlockResult = expandTextBlocks(current)
        if (textBlockResult.second) features.add("text blocks")
        current = textBlockResult.first

        // 2. Expand Java 16+ record declarations
        val recordResult = expandRecords(current)
        if (recordResult.second) features.add("records")
        current = recordResult.first

        // 3. Expand Java 25 IO.println / IO.print
        val ioRegex = Regex("""\bIO\.(println|print)\s*\(""")
        if (ioRegex.containsMatchIn(current)) {
            features.add("Java 25 IO")
            current = ioRegex.replace(current) { "System.out.${it.groupValues[1]}(" }
        }

        // 4. Lower List.of(...) / Set.of(...) / Map.of(...) / String.isBlank / repeat / lines / formatted
        val stdlibResult = lowerJava9To17LibraryCalls(current)
        if (stdlibResult.second) features.add("Java 9-17 stdlib")
        current = stdlibResult.first

        // 5. Lower pattern instanceof: if (expr instanceof Type varName) {
        val patResult = lowerPatternInstanceof(current)
        if (patResult.second) features.add("pattern instanceof")
        current = patResult.first

        // 6. Lower switch expressions and arrow case labels (including yield)
        val switchResult = lowerSwitchArrows(current)
        if (switchResult.second) features.add("switch expressions")
        current = switchResult.first

        // 7. Lower unnamed '_' lambda / catch params
        val unnamedResult = lowerUnnamedVariables(current)
        if (unnamedResult.second) features.add("unnamed _")
        current = unnamedResult.first

        // 8. Lower local `var x = expr;`
        val varResult = lowerLocalVarDeclarations(current)
        if (varResult.second) features.add("var inference")
        current = varResult.first

        // Track 1:1 / approximate line map back to original lines
        val origLines = source.replace("\r\n", "\n").lines()
        val outLines = current.lines()
        val lineMap = mutableMapOf<Int, Int>()
        val maxOrig = origLines.size.coerceAtLeast(1)
        outLines.forEachIndexed { idx, _ ->
            val approx = ((idx + 1).coerceAtMost(maxOrig))
            lineMap[idx + 1] = approx
        }
        return TranspileResult(
            java8Source = current,
            lineMap = lineMap,
            rewrittenFeatures = features.toList(),
        )
    }

    private fun expandTextBlocks(src: String): Pair<String, Boolean> {
        if (!src.contains("\"\"\"")) return src to false
        val sb = StringBuilder(src.length)
        var i = 0
        var changed = false
        while (i < src.length) {
            if (i + 2 < src.length && src[i] == '"' && src[i + 1] == '"' && src[i + 2] == '"') {
                changed = true
                i += 3
                // skip optional trailing whitespace and first newline after opening """
                while (i < src.length && (src[i] == ' ' || src[i] == '\t')) i++
                if (i < src.length && src[i] == '\n') i++
                val contentStart = i
                val closeIdx = src.indexOf("\"\"\"", contentStart)
                if (closeIdx < 0) {
                    sb.append("\"\"")
                    break
                }
                val rawBlock = src.substring(contentStart, closeIdx)
                i = closeIdx + 3
                val lines = rawBlock.split('\n')
                val nonEmpty = lines.filter { it.isNotBlank() }
                val minIndent = nonEmpty.minOfOrNull { line -> line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it } } ?: 0
                val stripped = lines.map { line ->
                    if (line.length >= minIndent) line.substring(minIndent) else line.trimStart()
                }
                val escaped = stripped.joinToString("\\n") { line ->
                    line.replace("\\", "\\\\").replace("\"", "\\\"")
                }
                sb.append('"').append(escaped).append('"')
            } else {
                sb.append(src[i])
                i++
            }
        }
        return sb.toString() to changed
    }

    private fun expandRecords(src: String): Pair<String, Boolean> {
        val recordHeaderRegex = Regex("""(?m)^([ \t]*)((?:public|private|protected|static|final)\s+)*record\s+([A-Za-z_]\w*)\s*\(([^)]*)\)\s*(?:implements\s+[^{]+)?\{""")
        if (!recordHeaderRegex.containsMatchIn(src)) return src to false
        var result = src
        var changed = false
        var safety = 0
        while (safety++ < 40) {
            val match = recordHeaderRegex.find(result) ?: break
            changed = true
            val indent = match.groupValues[1]
            val mods = match.groupValues[2].trim().let {
                if (it.isBlank()) "final" else if (it.contains("final")) it else "$it final"
            }
            val name = match.groupValues[3]
            val paramsRaw = match.groupValues[4].trim()
            val bodyOpenIdx = match.range.last
            val bodyCloseIdx = findMatchingBrace(result, bodyOpenIdx) ?: break
            val extraBody = result.substring(bodyOpenIdx + 1, bodyCloseIdx)

            val components = splitTopLevelComma(paramsRaw).mapNotNull { comp ->
                val clean = comp.trim()
                if (clean.isEmpty()) return@mapNotNull null
                val lastSpace = clean.lastIndexOf(' ')
                if (lastSpace <= 0) return@mapNotNull null
                val type = clean.substring(0, lastSpace).trim()
                val field = clean.substring(lastSpace + 1).trim()
                type to field
            }

            val expanded = buildString {
                appendLine("${indent}${mods} class $name {")
                components.forEach { (t, f) ->
                    appendLine("${indent}    private final $t $f;")
                }
                val ctorParams = components.joinToString(", ") { (t, f) -> "$t $f" }
                appendLine("${indent}    public $name($ctorParams) {")
                components.forEach { (_, f) ->
                    appendLine("${indent}        this.$f = $f;")
                }
                appendLine("${indent}    }")
                components.forEach { (t, f) ->
                    appendLine("${indent}    public $t $f() { return this.$f; }")
                }
                val toStringExpr = if (components.isEmpty()) {
                    "\"$name[]\""
                } else {
                    "\"$name[\" + " + components.joinToString(" + \", \" + ") { (_, f) -> "\"$f=\" + $f" } + " + \"]\""
                }
                appendLine("${indent}    @Override public String toString() { return $toStringExpr; }")
                appendLine("${indent}    @Override public boolean equals(Object o) {")
                appendLine("${indent}        if (this == o) return true;")
                appendLine("${indent}        if (!(o instanceof $name)) return false;")
                if (components.isEmpty()) {
                    appendLine("${indent}        return true;")
                } else {
                    appendLine("${indent}        $name other = ($name) o;")
                    val cmp = components.joinToString(" && ") { (_, f) -> "java.util.Objects.equals(this.$f, other.$f)" }
                    appendLine("${indent}        return $cmp;")
                }
                appendLine("${indent}    }")
                appendLine("${indent}    @Override public int hashCode() {")
                val hashArgs = components.joinToString(", ") { (_, f) -> f }
                appendLine("${indent}        return java.util.Objects.hash($hashArgs);")
                appendLine("${indent}    }")
                if (extraBody.isNotBlank()) {
                    appendLine(extraBody.trimEnd())
                }
                append("${indent}}")
            }

            result = result.substring(0, match.range.first) + expanded + result.substring(bodyCloseIdx + 1)
        }
        return result to changed
    }

    private fun wrapCompactTopLevelMain(src: String): Pair<String, Boolean> {
        val hasTypeDecl = Regex("""\b(class|interface|enum|record)\s+[A-Za-z_]\w*""").containsMatchIn(src)
        val hasTopMain = Regex("""(?m)^\s*(?:void|public\s+void|static\s+void)\s+main\s*\(""").containsMatchIn(src)
        if (hasTypeDecl || !hasTopMain) return src to false
        val pkgLines = mutableListOf<String>()
        val importLines = mutableListOf<String>()
        val bodyLines = mutableListOf<String>()
        src.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("package ") -> pkgLines.add(line)
                trimmed.startsWith("import ") -> importLines.add(line)
                else -> bodyLines.add(line)
            }
        }
        val normalizedBody = bodyLines.joinToString("\n").replace(
            Regex("""(?m)^\s*(?:public\s+|static\s+)*void\s+main\s*\(\s*\)"""),
            "public static void main(String[] args)",
        )
        val wrapped = buildString {
            pkgLines.forEach { appendLine(it) }
            importLines.forEach { appendLine(it) }
            appendLine("public class Main {")
            normalizedBody.lines().forEach { appendLine("    $it") }
            append("}")
        }
        return wrapped to true
    }

    private fun stripSealedAndPermits(src: String): Pair<String, Boolean> {
        var out = src
        var changed = false
        val sealedModRegex = Regex("""\b(?:non-sealed|sealed)\s+(?=(?:(?:public|private|protected|abstract|static|final)\s+)*(?:class|interface|record)\b)""")
        if (sealedModRegex.containsMatchIn(out)) {
            changed = true
            out = sealedModRegex.replace(out, "")
        }
        val permitsRegex = Regex("""\s+permits\s+[A-Za-z0-9_.,\s]+(?=\s*\{)""")
        if (permitsRegex.containsMatchIn(out)) {
            changed = true
            out = permitsRegex.replace(out, " ")
        }
        return out to changed
    }

    private fun lowerJava9To17LibraryCalls(src: String): Pair<String, Boolean> {
        var out = src
        var changed = false

        val listOfRegex = Regex("""\bList\.of\s*\(([^;{}]*?)\)""")
        if (listOfRegex.containsMatchIn(out)) {
            changed = true
            out = listOfRegex.replace(out) { m ->
                val args = m.groupValues[1].trim()
                if (args.isEmpty()) "java.util.Collections.emptyList()"
                else "new java.util.ArrayList<>(java.util.Arrays.asList($args))"
            }
        }

        val setOfRegex = Regex("""\bSet\.of\s*\(([^;{}]*?)\)""")
        if (setOfRegex.containsMatchIn(out)) {
            changed = true
            out = setOfRegex.replace(out) { m ->
                val args = m.groupValues[1].trim()
                if (args.isEmpty()) "java.util.Collections.emptySet()"
                else "new java.util.HashSet<>(java.util.Arrays.asList($args))"
            }
        }

        val mapOfRegex = Regex("""\bMap\.of\s*\(([^;{}]*?)\)""")
        if (mapOfRegex.containsMatchIn(out)) {
            changed = true
            out = mapOfRegex.replace(out) { m ->
                val rawArgs = m.groupValues[1].trim()
                if (rawArgs.isEmpty()) {
                    "java.util.Collections.emptyMap()"
                } else {
                    val parts = splitTopLevelComma(rawArgs).map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size == 2) {
                        "java.util.Collections.singletonMap(${parts[0]}, ${parts[1]})"
                    } else if (parts.size >= 4 && parts.size % 2 == 0) {
                        val puts = (0 until parts.size step 2).joinToString(" ") { idx ->
                            "put(${parts[idx]}, ${parts[idx + 1]});"
                        }
                        "new java.util.HashMap() {{ $puts }}"
                    } else {
                        "new java.util.HashMap<>()"
                    }
                }
            }
        }

        val isBlankRegex = Regex("""(\b[A-Za-z_]\w*(?:\([^)]*\))?|"[^"]*")\.isBlank\(\s*\)""")
        if (isBlankRegex.containsMatchIn(out)) {
            changed = true
            out = isBlankRegex.replace(out) { "(${it.groupValues[1]}.trim().isEmpty())" }
        }

        val repeatRegex = Regex("""("[^"]*"|\b[A-Za-z_]\w*(?:\([^)]*\))?)\.repeat\s*\(([^;{}]+?)\)""")
        if (repeatRegex.containsMatchIn(out)) {
            changed = true
            out = repeatRegex.replace(out) { m ->
                "String.join(\"\", java.util.Collections.nCopies(${m.groupValues[2].trim()}, ${m.groupValues[1]}))"
            }
        }

        val linesRegex = Regex("""("[^"]*"|\b[A-Za-z_]\w*(?:\([^)]*\))?)\.lines\(\s*\)""")
        if (linesRegex.containsMatchIn(out)) {
            changed = true
            out = linesRegex.replace(out) { m ->
                "java.util.Arrays.stream(${m.groupValues[1]}.split(\"\\\\R\"))"
            }
        }

        val formattedRegex = Regex("""("[^"]*")\s*\.formatted\s*\(([^)]*)\)""")
        if (formattedRegex.containsMatchIn(out)) {
            changed = true
            out = formattedRegex.replace(out) { "String.format(${it.groupValues[1]}, ${it.groupValues[2]})" }
        }

        return out to changed
    }

    private fun lowerPatternInstanceof(src: String): Pair<String, Boolean> {
        val patternRegex = Regex("""\bif\s*\(\s*([A-Za-z_][\w.]*)\s+instanceof\s+([A-Za-z_][\w.<>\[\]]*)\s+([A-Za-z_]\w*)\s*\)\s*\{""")
        if (!patternRegex.containsMatchIn(src)) return src to false
        val out = patternRegex.replace(src) { m ->
            val target = m.groupValues[1]
            val type = m.groupValues[2]
            val varName = m.groupValues[3]
            "if ($target instanceof $type) { $type $varName = ($type) $target;"
        }
        return out to true
    }

    private fun lowerSwitchArrows(src: String): Pair<String, Boolean> {
        var out = src
        var changed = false

        // Expression switch: return switch (expr) { case A -> v1; default -> v2; };
        val assignSwitchRegex = Regex("""(\breturn\b|[A-Za-z_][\w<>\[\]]*\s+[A-Za-z_]\w*\s*=|[A-Za-z_]\w*\s*=)\s*switch\s*\(""")
        var safety = 0
        var searchFrom = 0
        while (safety++ < 25) {
            val m = assignSwitchRegex.find(out, searchFrom) ?: break
            val openParen = m.range.last
            val closeParen = findMatchingParen(out, openParen)
            if (closeParen == null) {
                searchFrom = openParen + 1
                continue
            }
            var braceStart = closeParen + 1
            while (braceStart < out.length && out[braceStart].isWhitespace()) braceStart++
            if (braceStart >= out.length || out[braceStart] != '{') {
                searchFrom = openParen + 1
                continue
            }
            val braceEnd = findMatchingBrace(out, braceStart) ?: break
            val after = out.substring(braceEnd + 1).trimStart()
            if (!after.startsWith(";")) {
                searchFrom = braceStart + 1
                continue
            }
            val semiIdx = out.indexOf(';', braceEnd)
            val lhs = m.groupValues[1].trim()
            val switchCond = out.substring(openParen + 1, closeParen).trim()
            val body = out.substring(braceStart + 1, braceEnd)
            changed = true

            val converted = if (lhs == "return") {
                val cases = convertArrowBody(body, isReturn = true, targetVar = null)
                "switch ($switchCond) {\n$cases\n}"
            } else if (lhs.endsWith("=")) {
                val beforeEq = lhs.dropLast(1).trim()
                val tokens = beforeEq.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val varName = tokens.last()
                val declPrefix = if (tokens.size >= 2) "$beforeEq;" else ""
                val cases = convertArrowBody(body, isReturn = false, targetVar = varName)
                "$declPrefix\nswitch ($switchCond) {\n$cases\n}"
            } else {
                searchFrom = openParen + 1
                continue
            }
            out = out.substring(0, m.range.first) + converted + out.substring(semiIdx + 1)
            searchFrom = 0
        }

        // Statement arrow cases: case A, B -> stmt; or case A -> { ... }
        val stmtArrowRegex = Regex("""(?m)^([ \t]*)(case\s+[^:\n{}]+?|default)\s*->\s*([^\n{;]+;)""")
        if (stmtArrowRegex.containsMatchIn(out)) {
            changed = true
            out = stmtArrowRegex.replace(out) { m ->
                val indent = m.groupValues[1]
                val label = m.groupValues[2].trim()
                val stmt = m.groupValues[3].trim()
                val expandedLabels = expandMultiCaseLabel(label)
                val maybeBreak = if (stmt.startsWith("return ") || stmt.startsWith("throw ") || stmt == "break;") "" else " break;"
                "$indent$expandedLabels $stmt$maybeBreak"
            }
        }

        val blockArrowRegex = Regex("""(?m)^([ \t]*)(case\s+[^:\n{}]+?|default)\s*->\s*\{""")
        var bSafety = 0
        while (bSafety++ < 40) {
            val m = blockArrowRegex.find(out) ?: break
            changed = true
            val indent = m.groupValues[1]
            val label = expandMultiCaseLabel(m.groupValues[2].trim())
            val openIdx = m.range.last
            val closeIdx = findMatchingBrace(out, openIdx) ?: break
            val inner = out.substring(openIdx + 1, closeIdx).trimEnd()
            val needsBreak = !inner.contains("return ") && !inner.contains("throw ") && !inner.trimEnd().endsWith("break;")
            val replacement = "$indent$label {\n$inner\n$indent    ${if (needsBreak) "break;" else ""}\n$indent}"
            out = out.substring(0, m.range.first) + replacement + out.substring(closeIdx + 1)
        }

        return out to changed
    }

    private fun expandMultiCaseLabel(label: String): String {
        if (!label.startsWith("case ")) return "default:"
        val raw = label.removePrefix("case ").trim()
        val parts = splitTopLevelComma(raw).map { it.trim() }.filter { it.isNotEmpty() }
        return parts.joinToString(" ") { "case $it:" }
    }

    private fun convertArrowBody(body: String, isReturn: Boolean, targetVar: String?): String {
        var converted = body
        // Lower block arms inside switch expression: case X -> { ... yield expr; }
        val blockArmRegex = Regex("""(?m)(case\s+[^:\n{}]+?|default)\s*->\s*\{""")
        var safety = 0
        while (safety++ < 30) {
            val m = blockArmRegex.find(converted) ?: break
            val openIdx = m.range.last
            val closeIdx = findMatchingBrace(converted, openIdx) ?: break
            val label = expandMultiCaseLabel(m.groupValues[1].trim())
            var inner = converted.substring(openIdx + 1, closeIdx)
            val yieldRegex = Regex("""\byield\s+([^;\n]+);""")
            inner = yieldRegex.replace(inner) { ym ->
                val yExpr = ym.groupValues[1].trim()
                when {
                    isReturn -> "return $yExpr;"
                    targetVar != null -> "$targetVar = $yExpr; break;"
                    else -> "$yExpr;"
                }
            }
            converted = converted.substring(0, m.range.first) + "    $label {\n$inner\n    }" + converted.substring(closeIdx + 1)
        }
        val singleArmRegex = Regex("""(?m)(case\s+[^:\n{}]+?|default)\s*->\s*([^;\n{}]+)\s*;""")
        return singleArmRegex.replace(converted) { m ->
            val label = expandMultiCaseLabel(m.groupValues[1].trim())
            val expr = m.groupValues[2].trim()
            when {
                isReturn -> "    $label return $expr;"
                targetVar != null -> "    $label $targetVar = $expr; break;"
                else -> "    $label $expr;"
            }
        }
    }

    private fun lowerUnnamedVariables(src: String): Pair<String, Boolean> {
        var idx = 0
        var changed = false
        var out = src
        val lambdaSingle = Regex("""\(\s*_\s*\)\s*->""")
        if (lambdaSingle.containsMatchIn(out)) {
            changed = true
            out = lambdaSingle.replace(out) { "(_unused${idx++}) ->" }
        }
        val catchUnderscore = Regex("""catch\s*\(\s*([A-Za-z_][\w.]*)\s+_\s*\)""")
        if (catchUnderscore.containsMatchIn(out)) {
            changed = true
            out = catchUnderscore.replace(out) { "catch (${it.groupValues[1]} _unused${idx++})" }
        }
        val singleLambdaBare = Regex("""\b_\s*->""")
        if (singleLambdaBare.containsMatchIn(out)) {
            changed = true
            out = singleLambdaBare.replace(out) { "_unused${idx++} ->" }
        }
        return out to changed
    }

    private fun lowerLocalVarDeclarations(src: String): Pair<String, Boolean> {
        val varRegex = Regex("""(?m)^([ \t]*)(final\s+)?var\s+([A-Za-z_]\w*)\s*=\s*([^;\n]+);""")
        if (!varRegex.containsMatchIn(src)) return src to false
        var changed = false
        val out = varRegex.replace(src) { m ->
            changed = true
            val indent = m.groupValues[1]
            val finalMod = m.groupValues[2]
            val name = m.groupValues[3]
            val init = m.groupValues[4].trim()
            val inferred = inferJava8Type(init)
            "$indent${finalMod}$inferred $name = $init;"
        }
        val forVarRegex = Regex("""for\s*\(\s*(final\s+)?var\s+([A-Za-z_]\w*)\s*([:=])""")
        val out2 = forVarRegex.replace(out) { m ->
            changed = true
            val finalMod = m.groupValues[1]
            val name = m.groupValues[2]
            val sep = m.groupValues[3]
            if (sep == "=") "for (${finalMod}int $name =" else "for (${finalMod}Object $name :"
        }
        return out2 to changed
    }

    private fun inferJava8Type(expr: String): String {
        val t = expr.trim()
        return when {
            t.startsWith("\"") || t.startsWith("String.") -> "String"
            t == "true" || t == "false" || t.contains(" == ") || t.contains(" != ") || t.contains(" && ") || t.contains(" || ") -> "boolean"
            t.startsWith("new ") -> {
                val afterNew = t.removePrefix("new ").trim()
                val parenIdx = afterNew.indexOf('(')
                val bracketIdx = afterNew.indexOf('[')
                val cut = listOf(parenIdx, bracketIdx).filter { it > 0 }.minOrNull() ?: afterNew.length
                val rawType = afterNew.substring(0, cut).trim()
                when {
                    bracketIdx > 0 && (parenIdx < 0 || bracketIdx < parenIdx) -> {
                        val elem = rawType
                        val dims = "\\[[^\\]]*\\]".toRegex().findAll(afterNew).count().coerceAtLeast(1)
                        elem + "[]".repeat(dims)
                    }
                    rawType.endsWith("<>") -> rawType.dropLast(2)
                    else -> rawType
                }
            }
            t.startsWith("Color.") -> "int"
            t.endsWith("f", ignoreCase = true) && t.dropLast(1).toFloatOrNull() != null -> "float"
            t.endsWith("L", ignoreCase = true) && t.dropLast(1).toLongOrNull() != null -> "long"
            t.contains('.') && t.toDoubleOrNull() != null -> "double"
            t.toIntOrNull() != null -> "int"
            t.contains("findViewById(") -> "View"
            t.contains("Math.random") || t.contains("Math.sin") || t.contains("Math.cos") || t.contains("Math.sqrt") -> "double"
            t.startsWith("System.currentTimeMillis") || t.startsWith("System.nanoTime") -> "long"
            else -> "Object"
        }
    }

    private fun splitTopLevelComma(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var angle = 0
        var paren = 0
        var start = 0
        for (i in input.indices) {
            when (input[i]) {
                '<' -> angle++
                '>' -> if (angle > 0) angle--
                '(' -> paren++
                ')' -> if (paren > 0) paren--
                ',' -> if (angle == 0 && paren == 0) {
                    out.add(input.substring(start, i))
                    start = i + 1
                }
            }
        }
        out.add(input.substring(start))
        return out
    }

    private fun findMatchingBrace(src: String, openIdx: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIdx until src.length) {
            val c = src[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun findMatchingParen(src: String, openIdx: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIdx until src.length) {
            val c = src[i]
            if (escape) {
                escape = false
                continue
            }
            if (inString) {
                if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }
}
