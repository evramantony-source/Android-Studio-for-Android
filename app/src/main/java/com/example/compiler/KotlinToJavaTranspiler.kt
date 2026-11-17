package com.example.compiler

/**
 * Lightweight, self-contained Kotlin-to-Java 8 transpiler for on-device offline compilation
 * through Eclipse JDT (ecj) and Dalvik dx. Designed specifically for Android SDK Kotlin sources
 * without requiring desktop JVM compiler dependencies (~60MB kotlin-compiler-embeddable).
 */
object KotlinToJavaTranspiler {

    data class TranspileResult(
        val javaSource: String,
        val primaryClassName: String,
        val packageName: String,
        val lineMap: Map<Int, Int>, // 1-based Java line -> 1-based Kotlin line
    )

    private enum class BlockKind {
        CLASS,
        COMPANION,
        ANON_FIELD,
        SAM_LAMBDA,
        METHOD_OR_BLOCK,
    }

    private val KOTLIN_TO_JAVA_TYPES = mapOf(
        "Int" to "int",
        "Long" to "long",
        "Double" to "double",
        "Float" to "float",
        "Boolean" to "boolean",
        "Char" to "char",
        "Byte" to "byte",
        "Short" to "short",
        "Unit" to "void",
        "Any" to "Object",
        "String" to "String",
        "CharSequence" to "CharSequence",
        "IntArray" to "int[]",
        "LongArray" to "long[]",
        "FloatArray" to "float[]",
        "DoubleArray" to "double[]",
        "BooleanArray" to "boolean[]",
        "ByteArray" to "byte[]",
    )

    private val BOXED_TYPES = mapOf(
        "int" to "Integer",
        "long" to "Long",
        "double" to "Double",
        "float" to "Float",
        "boolean" to "Boolean",
        "char" to "Character",
        "byte" to "Byte",
        "short" to "Short",
        "void" to "Void",
    )

    private val PROTECTED_OVERRIDES = setOf(
        "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onRestart",
        "onSaveInstanceState", "onRestoreInstanceState", "onActivityResult", "onDraw", "onSizeChanged",
        "onMeasure", "onLayout",
    )

    fun transpile(
        kotlinSource: String,
        fallbackPackage: String = "com.example.app",
        fileName: String = "MainActivity.kt",
    ): TranspileResult {
        val rawLines = kotlinSource.replace("\r\n", "\n").split('\n')
        val outLines = mutableListOf<String>()
        val lineMap = mutableMapOf<Int, Int>()

        var packageName = fallbackPackage
        val defaultClassName = fileName.substringAfterLast('/').removeSuffix(".kt").ifBlank { "MainActivity" }
        var primaryClassName = defaultClassName

        val varTypes = mutableMapOf<String, String>()
        val fnReturnTypes = mutableMapOf<String, String>()
        val arrayVars = mutableSetOf<String>()
        val listVars = mutableSetOf<String>()
        val blockStack = ArrayDeque<BlockKind>()
        val classStack = mutableListOf<String>()

        fun emit(javaLine: String, ktLineNumber: Int) {
            outLines.add(javaLine)
            lineMap[outLines.size] = ktLineNumber
        }

        fun recordVarType(name: String, javaType: String) {
            varTypes[name] = javaType
            if (javaType.endsWith("[]")) {
                arrayVars.add(name)
                listVars.remove(name)
            } else if (javaType.contains("List") || javaType.contains("Map") || javaType.contains("Set")) {
                listVars.add(name)
                arrayVars.remove(name)
            }
        }

        val items = collectStatementsWithLineNumbers(rawLines)

        // Pre-scan function signatures so forward calls can resolve return types during var inference
        for ((_, text) in items) {
            val t = text.trim()
            if (isFunctionDeclaration(t)) {
                val fn = convertFunctionHeader(t) { _, _ -> }
                val nameMatch = Regex("""fun\s+([A-Za-z_]\w*)\s*\(""").find(t)
                if (nameMatch != null) {
                    fnReturnTypes[nameMatch.groupValues[1]] = fn.returnType
                }
            }
        }

        var emittedDefaultPackage = false
        for ((_, text) in items) {
            val trimmed = text.trim()
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
                break
            }
        }

        var idx = 0
        while (idx < items.size) {
            val (lineNum, rawStmt) = items[idx]
            val trimmed = rawStmt.trim()

            if (trimmed.isEmpty()) {
                emit("", lineNum)
                idx++
                continue
            }

            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                emit(rawStmt, lineNum)
                idx++
                continue
            }

            // Package declaration
            if (trimmed.startsWith("package ")) {
                val cleanPkg = trimmed.removePrefix("package ").removeSuffix(";").trim()
                packageName = cleanPkg
                emit("package $cleanPkg;", lineNum)
                emittedDefaultPackage = true
                idx++
                continue
            }

            if (!emittedDefaultPackage && classStack.isEmpty()) {
                emit("package $packageName;", lineNum)
                emittedDefaultPackage = true
            }

            // Import declaration
            if (trimmed.startsWith("import ")) {
                val imp = trimmed.removePrefix("import ").removeSuffix(";").trim()
                if (!imp.startsWith("kotlin.") && !imp.startsWith("kotlinx.")) {
                    emit("import $imp;", lineNum)
                }
                idx++
                continue
            }

            // Closing brace line (`}` or `} else {`)
            if (trimmed == "}") {
                val kind = blockStack.removeLastOrNull()
                when (kind) {
                    BlockKind.COMPANION -> Unit // omitted
                    BlockKind.ANON_FIELD -> emit("${leadingIndent(rawStmt)}};", lineNum)
                    BlockKind.SAM_LAMBDA -> emit("${leadingIndent(rawStmt)}} });", lineNum)
                    BlockKind.CLASS -> {
                        if (classStack.isNotEmpty()) classStack.removeAt(classStack.lastIndex)
                        emit("${leadingIndent(rawStmt)}}", lineNum)
                    }
                    else -> emit("${leadingIndent(rawStmt)}}", lineNum)
                }
                idx++
                continue
            }

            if (trimmed == "} else {" || trimmed.startsWith("} else if ")) {
                // Pops the `if` block and pushes the `else` block
                blockStack.removeLastOrNull()
                blockStack.addLast(BlockKind.METHOD_OR_BLOCK)
                val rewritten = if (trimmed == "} else {") {
                    trimmed
                } else {
                    val condPart = trimmed.removePrefix("}").removeSuffix("{").trim()
                    "} ${rewriteExpression(condPart, arrayVars, listVars, varTypes)} {"
                }
                emit("${leadingIndent(rawStmt)}$rewritten", lineNum)
                idx++
                continue
            }

            // companion object {
            if (trimmed.startsWith("companion object")) {
                if (trimmed.endsWith("{")) {
                    blockStack.addLast(BlockKind.COMPANION)
                }
                emit("${leadingIndent(rawStmt)}// companion object", lineNum)
                idx++
                continue
            }

            // Class declaration
            if (isClassDeclaration(trimmed)) {
                val converted = convertClassHeader(
                    trimmed = trimmed,
                    isTopLevel = classStack.isEmpty(),
                    recordField = ::recordVarType,
                )
                if (classStack.isEmpty()) {
                    primaryClassName = converted.className
                }
                val indent = leadingIndent(rawStmt)
                emit("$indent${converted.headerLine}", lineNum)
                if (converted.opensBrace) {
                    classStack.add(converted.className)
                    blockStack.addLast(BlockKind.CLASS)
                }
                converted.constructorLines.forEach { cLine ->
                    emit("$indent    $cLine", lineNum)
                }
                if (!converted.opensBrace && converted.constructorLines.isNotEmpty()) {
                    // Single-line data class: e.g. `private class Stroke(val path: Path, val color: Int)`
                    emit("$indent}", lineNum)
                } else if (!converted.opensBrace) {
                    emit("$indent}", lineNum)
                }
                idx++
                continue
            }

            // Function declaration
            if (isFunctionDeclaration(trimmed)) {
                val indent = leadingIndent(rawStmt)
                val fn = convertFunctionHeader(trimmed, recordParam = ::recordVarType)
                if (fn.singleExprBody != null) {
                    val rewrittenBody = rewriteExpression(fn.singleExprBody, arrayVars, listVars, varTypes)
                    val retPrefix = if (fn.returnType == "void") "" else "return "
                    emit("$indent${fn.headerWithoutBrace} { $retPrefix$rewrittenBody; }", lineNum)
                } else {
                    emit("$indent${fn.headerWithoutBrace} {", lineNum)
                    if (fn.opensBrace) {
                        blockStack.addLast(BlockKind.METHOD_OR_BLOCK)
                    }
                }
                idx++
                continue
            }

            // `init {` -> Java instance initializer block `{` (compatible with any primary constructor/super call)
            if (trimmed.startsWith("init") && trimmed.endsWith("{")) {
                emit("${leadingIndent(rawStmt)}{", lineNum)
                blockStack.addLast(BlockKind.METHOD_OR_BLOCK)
                idx++
                continue
            }

            // `when (...) {` or `when {`
            if (trimmed.startsWith("when") && trimmed.endsWith("{")) {
                val endIdx = findMatchingBlockEnd(items, idx)
                val blockItems = items.subList(idx, endIdx + 1)
                val convertedLines = convertWhenBlock(blockItems, arrayVars, listVars, varTypes)
                convertedLines.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                idx = endIdx + 1
                continue
            }

            // `for (...) {`
            if (trimmed.startsWith("for") && trimmed.contains(" in ")) {
                val converted = convertForHeader(trimmed, arrayVars, listVars, varTypes, ::recordVarType)
                if (trimmed.endsWith("{")) {
                    blockStack.addLast(BlockKind.METHOD_OR_BLOCK)
                }
                emit("${leadingIndent(rawStmt)}$converted", lineNum)
                idx++
                continue
            }

            // Variable / Field declaration
            if (isVariableDeclaration(trimmed)) {
                val isField = classStack.isNotEmpty() && blockStack.lastOrNull() == BlockKind.CLASS
                val (converted, openedBlock) = convertVariableDeclaration(
                    rawLine = rawStmt,
                    isField = isField,
                    inCompanion = blockStack.contains(BlockKind.COMPANION),
                    arrayVars = arrayVars,
                    listVars = listVars,
                    varTypes = varTypes,
                    fnReturnTypes = fnReturnTypes,
                    recordVar = ::recordVarType,
                )
                if (openedBlock != null) {
                    blockStack.addLast(openedBlock)
                }
                emit(converted, lineNum)
                idx++
                continue
            }

            // General statement / control flow / SAM lambda
            val (convertedStmt, openedKind) = convertGeneralStatement(rawStmt, arrayVars, listVars, varTypes)
            if (openedKind != null) {
                blockStack.addLast(openedKind)
            }
            emit(convertedStmt, lineNum)
            idx++
        }

        return TranspileResult(
            javaSource = outLines.joinToString("\n") + "\n",
            primaryClassName = primaryClassName,
            packageName = packageName,
            lineMap = lineMap,
        )
    }

    private fun collectStatementsWithLineNumbers(rawLines: List<String>): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var inBlockComment = false
        var i = 0
        while (i < rawLines.size) {
            val line = rawLines[i]
            val trimmed = line.trim()
            val lineNum = i + 1

            if (inBlockComment) {
                result.add(lineNum to line)
                if (trimmed.contains("*/")) inBlockComment = false
                i++
                continue
            }
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true
                result.add(lineNum to line)
                i++
                continue
            }

            if (!trimmed.startsWith("//") && hasUnclosedParensOrTrailingOp(trimmed) && i + 1 < rawLines.size) {
                val indent = leadingIndent(line)
                val joined = StringBuilder(trimmed)
                var j = i + 1
                while (j < rawLines.size && hasUnclosedParensOrTrailingOp(joined.toString())) {
                    val nextTrimmed = rawLines[j].trim()
                    if (nextTrimmed.isNotEmpty() && !nextTrimmed.startsWith("//")) {
                        joined.append(' ').append(nextTrimmed)
                    }
                    j++
                    if (nextTrimmed.endsWith("{") || nextTrimmed.endsWith(")")) {
                        if (parenBalance(joined.toString()) <= 0 && !endsWithContinuationOp(joined.toString())) {
                            break
                        }
                    }
                }
                result.add(lineNum to "$indent$joined")
                i = j
                continue
            }

            result.add(lineNum to line)
            i++
        }
        return result
    }

    private fun hasUnclosedParensOrTrailingOp(text: String): Boolean {
        if (parenBalance(text) > 0) return true
        return endsWithContinuationOp(text)
    }

    private fun endsWithContinuationOp(text: String): Boolean {
        val t = text.trimEnd()
        return t.endsWith("=") || (t.endsWith("+") && !t.endsWith("++")) || t.endsWith("||") || t.endsWith("&&") ||
            t.endsWith(",") || t.endsWith(":") || t.endsWith("->")
    }

    private fun parenBalance(text: String): Int {
        var balance = 0
        var inStr = false
        var escape = false
        for (c in text) {
            if (escape) { escape = false; continue }
            if (c == '\\' && inStr) { escape = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '(') balance++
                else if (c == ')') balance--
            }
        }
        return balance
    }

    private fun findMatchingBlockEnd(items: List<Pair<Int, String>>, startIdx: Int): Int {
        var depth = 0
        for (i in startIdx until items.size) {
            val s = items[i].second
            depth += countBracesOutsideStrings(s)
            if (depth <= 0 && i > startIdx) return i
        }
        return items.lastIndex
    }

    private fun countBracesOutsideStrings(s: String): Int {
        var delta = 0
        var inStr = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inStr) { escape = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '/' && i + 1 < s.length && s[i + 1] == '/') break
                if (c == '{') delta++
                else if (c == '}') delta--
            }
        }
        return delta
    }

    private fun isClassDeclaration(trimmed: String): Boolean {
        return Regex("""^(?:(?:public|private|internal|protected|open|abstract|final|data|inner)\s+)*class\s+[A-Za-z_]\w*""")
            .containsMatchIn(trimmed)
    }

    private data class ConvertedClass(
        val className: String,
        val headerLine: String,
        val opensBrace: Boolean,
        val constructorLines: List<String>,
    )

    private fun convertClassHeader(
        trimmed: String,
        isTopLevel: Boolean,
        recordField: (String, String) -> Unit,
    ): ConvertedClass {
        val modMatch = Regex("""^((?:(?:public|private|internal|protected|open|abstract|final|data|inner)\s+)*)class\s+([A-Za-z_]\w*)(.*)$""").find(trimmed)
        val rawMods = modMatch?.groupValues?.get(1)?.trim().orEmpty()
        val className = modMatch?.groupValues?.get(2) ?: "MainActivity"
        var rest = modMatch?.groupValues?.get(3)?.trim().orEmpty()

        val hasOpenBrace = rest.endsWith("{")
        if (hasOpenBrace) rest = rest.dropLast(1).trim()

        val javaVis = when {
            rawMods.contains("private") -> "private"
            rawMods.contains("protected") -> "protected"
            isTopLevel -> "public"
            else -> "private"
        }
        val extraMod = buildString {
            if (!isTopLevel && !rawMods.contains("inner")) append("static ")
            if (rawMods.contains("abstract")) append("abstract ")
        }

        var ctorParamsRaw = ""
        if (rest.startsWith("(")) {
            val closeIdx = findClosingParen(rest, 0)
            if (closeIdx > 0) {
                ctorParamsRaw = rest.substring(1, closeIdx)
                rest = rest.substring(closeIdx + 1).trim()
            }
        }

        var extendsClause = ""
        var superArgs = ""
        val implementsList = mutableListOf<String>()
        if (rest.startsWith(":")) {
            val supers = splitTopLevelComma(rest.removePrefix(":").trim())
            supers.forEach { sup ->
                val s = sup.trim()
                val parenIdx = s.indexOf('(')
                if (parenIdx >= 0) {
                    extendsClause = " extends ${s.substring(0, parenIdx).trim()}"
                    val closeParen = findClosingParen(s, parenIdx)
                    if (closeParen > parenIdx) {
                        superArgs = s.substring(parenIdx + 1, closeParen).trim()
                    }
                } else {
                    implementsList.add(s)
                }
            }
        }

        val implementsClause = if (implementsList.isNotEmpty()) {
            " implements ${implementsList.joinToString(", ")}"
        } else ""

        val headerLine = "$javaVis ${extraMod}class $className$extendsClause$implementsClause {"

        val constructorLines = mutableListOf<String>()
        if (ctorParamsRaw.isNotBlank()) {
            val params = splitTopLevelComma(ctorParamsRaw)
            val ctorArgs = mutableListOf<String>()
            val ctorBody = mutableListOf<String>()
            if (superArgs.isNotBlank()) {
                ctorBody.add("super($superArgs);")
            }
            params.forEach { p ->
                val clean = p.trim()
                val isProp = clean.startsWith("val ") || clean.startsWith("var ")
                val isFinal = clean.startsWith("val ")
                val withoutKeyword = clean.removePrefix("val ").removePrefix("var ").trim()
                val name = withoutKeyword.substringBefore(':').trim()
                val ktType = withoutKeyword.substringAfter(':', "Object").trim()
                val jType = mapKotlinType(ktType)
                if (isProp) {
                    recordField(name, jType)
                    constructorLines.add("${if (isFinal) "final " else ""}$jType $name;")
                    ctorBody.add("this.$name = $name;")
                }
                ctorArgs.add("$jType $name")
            }
            val ctorVis = if (javaVis == "private") "" else "public "
            constructorLines.add("$ctorVis$className(${ctorArgs.joinToString(", ")}) { ${ctorBody.joinToString(" ")} }")
        }

        return ConvertedClass(
            className = className,
            headerLine = headerLine,
            opensBrace = hasOpenBrace,
            constructorLines = constructorLines,
        )
    }

    private fun isFunctionDeclaration(trimmed: String): Boolean {
        return Regex("""^(?:(?:override|private|protected|public|internal|open|final|suspend|inline)\s+)*fun\s+[A-Za-z_]\w*\s*\(""").containsMatchIn(trimmed)
    }

    private data class ConvertedFunction(
        val returnType: String,
        val headerWithoutBrace: String,
        val opensBrace: Boolean,
        val singleExprBody: String? = null,
    )

    private fun convertFunctionHeader(
        trimmed: String,
        recordParam: (String, String) -> Unit,
    ): ConvertedFunction {
        val m = Regex("""^((?:(?:override|private|protected|public|internal|open|final|suspend|inline)\s+)*)fun\s+([A-Za-z_]\w*)\s*\(""").find(trimmed)!!
        val mods = m.groupValues[1]
        val fnName = m.groupValues[2]
        val openParenIdx = m.range.last
        val closeParenIdx = findClosingParen(trimmed, openParenIdx)
        val paramsRaw = if (closeParenIdx > openParenIdx) trimmed.substring(openParenIdx + 1, closeParenIdx) else ""
        var afterParen = if (closeParenIdx + 1 < trimmed.length) trimmed.substring(closeParenIdx + 1).trim() else ""

        var opensBrace = false
        var singleExpr: String? = null
        if (afterParen.endsWith("{")) {
            opensBrace = true
            afterParen = afterParen.dropLast(1).trim()
        } else if (afterParen.contains("=")) {
            val eqIdx = findTopLevelChar(afterParen, '=')
            if (eqIdx >= 0) {
                singleExpr = afterParen.substring(eqIdx + 1).trim()
                afterParen = afterParen.substring(0, eqIdx).trim()
            }
        }

        val returnKtType = if (afterParen.startsWith(":")) {
            afterParen.removePrefix(":").trim()
        } else {
            "Unit"
        }
        val returnJavaType = mapKotlinType(returnKtType)

        val javaParams = splitTopLevelComma(paramsRaw).mapNotNull { param ->
            val clean = param.trim().substringBefore('=').trim()
            if (clean.isEmpty()) return@mapNotNull null
            val pName = clean.substringBefore(':').trim()
            val pKtType = clean.substringAfter(':', "Object").trim()
            val pJavaType = mapKotlinType(pKtType)
            recordParam(pName, pJavaType)
            "$pJavaType $pName"
        }

        val isOverride = mods.contains("override")
        val vis = when {
            mods.contains("private") -> "private"
            mods.contains("protected") -> "protected"
            mods.contains("public") -> "public"
            isOverride && fnName in PROTECTED_OVERRIDES -> "protected"
            else -> "public"
        }
        val overrideAnno = if (isOverride) "@Override " else ""

        return ConvertedFunction(
            returnType = returnJavaType,
            headerWithoutBrace = "$overrideAnno$vis $returnJavaType $fnName(${javaParams.joinToString(", ")})",
            opensBrace = opensBrace,
            singleExprBody = singleExpr,
        )
    }

    private fun isVariableDeclaration(trimmed: String): Boolean {
        return Regex("""^(?:(?:private|protected|public|internal|const|lateinit|override)\s+)*(?:val|var)\s+[A-Za-z_]\w*""").containsMatchIn(trimmed)
    }

    private fun convertVariableDeclaration(
        rawLine: String,
        isField: Boolean,
        inCompanion: Boolean,
        arrayVars: MutableSet<String>,
        listVars: MutableSet<String>,
        varTypes: MutableMap<String, String>,
        fnReturnTypes: Map<String, String>,
        recordVar: (String, String) -> Unit,
    ): Pair<String, BlockKind?> {
        val indent = leadingIndent(rawLine)
        val trimmed = rawLine.trim().removeSuffix(";")
        val m = Regex("""^((?:(?:private|protected|public|internal|const|lateinit|override)\s+)*)(val|var)\s+([A-Za-z_]\w*)(.*)$""").find(trimmed)
            ?: return "$indent$trimmed;" to null

        val mods = m.groupValues[1]
        val keyword = m.groupValues[2]
        val varName = m.groupValues[3]
        val rest = m.groupValues[4].trim()

        val eqIdx = findTopLevelChar(rest, '=')
        val declaredKtType = if (eqIdx >= 0) {
            rest.substring(0, eqIdx).trim().removePrefix(":").trim()
        } else {
            rest.removePrefix(":").trim()
        }
        val rawInitExpr = if (eqIdx >= 0) rest.substring(eqIdx + 1).trim() else null

        val inferredJavaType = if (declaredKtType.isNotBlank()) {
            mapKotlinType(declaredKtType)
        } else if (rawInitExpr != null) {
            inferJavaType(rawInitExpr, varTypes, fnReturnTypes)
        } else {
            "Object"
        }

        recordVar(varName, inferredJavaType)

        val javaVis = when {
            mods.contains("private") -> "private "
            mods.contains("protected") -> "protected "
            mods.contains("public") -> "public "
            inCompanion -> "public "
            else -> ""
        }
        val staticMod = if (inCompanion) "static " else ""
        val finalMod = when {
            mods.contains("const") -> "final "
            keyword == "val" && (!isField || rawInitExpr != null) -> "final "
            else -> ""
        }

        if (rawInitExpr == null) {
            return "$indent$javaVis$staticMod$finalMod$inferredJavaType $varName;" to null
        }

        val opensBlock = rawInitExpr.endsWith("{")
        val initWithoutBrace = if (opensBlock) rawInitExpr.dropLast(1).trim() else rawInitExpr
        val rewrittenInit = rewriteExpression(
            expr = initWithoutBrace,
            arrayVars = arrayVars,
            listVars = listVars,
            varTypes = varTypes,
            expectedArrayType = inferredJavaType.takeIf { it.endsWith("[]") },
        )

        return if (opensBlock) {
            "$indent$javaVis$staticMod$finalMod$inferredJavaType $varName = $rewrittenInit {" to BlockKind.ANON_FIELD
        } else {
            "$indent$javaVis$staticMod$finalMod$inferredJavaType $varName = $rewrittenInit;" to null
        }
    }

    private fun convertWhenBlock(
        blockItems: List<Pair<Int, String>>,
        arrayVars: MutableSet<String>,
        listVars: MutableSet<String>,
        varTypes: MutableMap<String, String>,
    ): List<Pair<String, Int>> {
        val header = blockItems.first()
        val headerIndent = leadingIndent(header.second)
        val headerTrimmed = header.second.trim()
        val parenStart = headerTrimmed.indexOf('(')
        val parenEnd = if (parenStart >= 0) findClosingParen(headerTrimmed, parenStart) else -1
        val subject = if (parenStart >= 0 && parenEnd > parenStart) {
            rewriteExpression(headerTrimmed.substring(parenStart + 1, parenEnd).trim(), arrayVars, listVars, varTypes)
        } else null

        val output = mutableListOf<Pair<String, Int>>()
        var branchIndex = 0
        var i = 1
        while (i < blockItems.size - 1) {
            val (lineNum, rawLine) = blockItems[i]
            val t = rawLine.trim()
            if (t.isEmpty() || t.startsWith("//")) {
                output.add(rawLine to lineNum)
                i++
                continue
            }
            val arrowIdx = findTopLevelArrow(t)
            if (arrowIdx >= 0) {
                val patternRaw = t.substring(0, arrowIdx).trim()
                val bodyStartRaw = t.substring(arrowIdx + 2).trim()
                val cond = buildWhenCondition(subject, patternRaw, arrayVars, listVars, varTypes)
                val branchPrefix = when {
                    patternRaw == "else" -> "${headerIndent}else"
                    branchIndex == 0 -> "${headerIndent}if ($cond)"
                    else -> "${headerIndent}else if ($cond)"
                }
                branchIndex++

                if (bodyStartRaw.endsWith("{")) {
                    val branchEnd = findMatchingBlockEnd(blockItems, i)
                    output.add("$branchPrefix {" to lineNum)
                    for (j in i + 1 until branchEnd) {
                        val (bLine, bRaw) = blockItems[j]
                        val (stmt, _) = convertGeneralStatement(bRaw, arrayVars, listVars, varTypes)
                        output.add(stmt to bLine)
                    }
                    output.add("$headerIndent}" to blockItems[branchEnd].first)
                    i = branchEnd + 1
                    continue
                } else {
                    val (stmt, _) = convertGeneralStatement("$headerIndent    $bodyStartRaw", arrayVars, listVars, varTypes)
                    output.add("$branchPrefix { ${stmt.trim()} }" to lineNum)
                }
            } else {
                val (stmt, _) = convertGeneralStatement(rawLine, arrayVars, listVars, varTypes)
                output.add(stmt to lineNum)
            }
            i++
        }
        return output
    }

    private fun buildWhenCondition(
        subject: String?,
        patternRaw: String,
        arrayVars: MutableSet<String>,
        listVars: MutableSet<String>,
        varTypes: MutableMap<String, String>,
    ): String {
        if (patternRaw == "else") return "true"
        val branches = splitTopLevelComma(patternRaw)
        return branches.joinToString(" || ") { rawCase ->
            val c = rewriteExpression(rawCase.trim(), arrayVars, listVars, varTypes)
            when {
                subject == null -> c
                c.startsWith("\"") || c.startsWith("String.") -> "$subject.equals($c)"
                c == "null" -> "$subject == null"
                else -> "$subject == $c"
            }
        }
    }

    private fun convertForHeader(
        trimmed: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
        recordVar: (String, String) -> Unit,
    ): String {
        val openIdx = trimmed.indexOf('(')
        val closeIdx = findClosingParen(trimmed, openIdx)
        val inside = trimmed.substring(openIdx + 1, closeIdx).trim()
        val hasBrace = trimmed.endsWith("{")
        val suffix = if (hasBrace) " {" else ""

        val parts = inside.split(" in ", limit = 2)
        val loopVar = parts[0].trim()
        val rangeOrCol = parts.getOrNull(1)?.trim().orEmpty()

        return when {
            rangeOrCol.endsWith(".indices") -> {
                val target = rangeOrCol.removeSuffix(".indices").trim()
                val sizeExpr = if (target in arrayVars) "$target.length" else "$target.size()"
                recordVar(loopVar, "int")
                "for (int $loopVar = 0; $loopVar < $sizeExpr; $loopVar++)$suffix"
            }
            rangeOrCol.contains(" until ") -> {
                val r = rangeOrCol.split(" until ", limit = 2)
                val start = rewriteExpression(r[0].trim(), arrayVars, listVars, varTypes)
                val end = rewriteExpression(r[1].trim(), arrayVars, listVars, varTypes)
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar < $end; $loopVar++)$suffix"
            }
            rangeOrCol.contains(" downTo ") -> {
                val r = rangeOrCol.split(" downTo ", limit = 2)
                val start = rewriteExpression(r[0].trim(), arrayVars, listVars, varTypes)
                val end = rewriteExpression(r[1].trim(), arrayVars, listVars, varTypes)
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar >= $end; $loopVar--)$suffix"
            }
            rangeOrCol.contains("..") -> {
                val r = rangeOrCol.split("..", limit = 2)
                val start = rewriteExpression(r[0].trim(), arrayVars, listVars, varTypes)
                val end = rewriteExpression(r[1].trim(), arrayVars, listVars, varTypes)
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar <= $end; $loopVar++)$suffix"
            }
            else -> {
                val colExpr = rewriteExpression(rangeOrCol, arrayVars, listVars, varTypes)
                val elemType = inferElementType(rangeOrCol, varTypes)
                recordVar(loopVar, elemType)
                "for ($elemType $loopVar : $colExpr)$suffix"
            }
        }
    }

    private fun inferElementType(colExpr: String, varTypes: Map<String, String>): String {
        val declared = varTypes[colExpr.trim()]
        if (declared != null) {
            if (declared.endsWith("[]")) return declared.removeSuffix("[]")
            val lt = declared.indexOf('<')
            val gt = declared.lastIndexOf('>')
            if (lt >= 0 && gt > lt) return unboxType(declared.substring(lt + 1, gt).trim())
        }
        return "Object"
    }

    private fun convertGeneralStatement(
        rawLine: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): Pair<String, BlockKind?> {
        val indent = leadingIndent(rawLine)
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed == "{" || trimmed == "}") {
            val blockKind = if (trimmed == "{") BlockKind.METHOD_OR_BLOCK else null
            return rawLine to blockKind
        }

        // Single-line SAM listener: `btn.setOnClickListener { doSomething() }`
        val singleLineSam = Regex("""^([A-Za-z_][\w.]*)\.setOnClickListener\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?(.+)\s*\}$""").find(trimmed)
        if (singleLineSam != null) {
            val target = singleLineSam.groupValues[1]
            val param = singleLineSam.groupValues[2].ifBlank { "_v" }
            val body = rewriteStatementProperties(singleLineSam.groupValues[3].trim(), arrayVars, listVars, varTypes)
            val semi = if (body.endsWith(";")) "" else ";"
            return "$indent$target.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View $param) { $body$semi } });" to null
        }

        // Multi-line SAM listener start: `btn.setOnClickListener {` or `btn.setOnClickListener { v ->`
        val samLambdaStart = Regex("""^([A-Za-z_][\w.]*)\.setOnClickListener\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (samLambdaStart != null) {
            val target = samLambdaStart.groupValues[1]
            val param = samLambdaStart.groupValues[2].ifBlank { "_v" }
            return "$indent$target.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View $param) {" to BlockKind.SAM_LAMBDA
        }

        // Control headers ending with `{`
        if (trimmed.endsWith("{")) {
            val rewritten = rewriteExpression(trimmed.dropLast(1).trim(), arrayVars, listVars, varTypes)
            return "$indent$rewritten {" to BlockKind.METHOD_OR_BLOCK
        }

        // Trailing else
        if (trimmed == "} else {" || trimmed == "else {") {
            return "$indent$trimmed" to BlockKind.METHOD_OR_BLOCK
        }

        val rewritten = rewriteStatementProperties(trimmed, arrayVars, listVars, varTypes)
        val needsSemi = !rewritten.endsWith(";") && !rewritten.endsWith("{") && !rewritten.endsWith("}") && !rewritten.endsWith(":")
        return (if (needsSemi) "$indent$rewritten;" else "$indent$rewritten") to null
    }

    private fun rewriteStatementProperties(
        stmt: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): String {
        val propMap = mapOf(
            "text" to "setText",
            "textSize" to "setTextSize",
            "textColor" to "setTextColor",
            "textAlign" to "setTextAlign",
            "textScaleX" to "setTextScaleX",
            "typeface" to "setTypeface",
            "hint" to "setHint",
            "hintTextColor" to "setHintTextColor",
            "gravity" to "setGravity",
            "orientation" to "setOrientation",
            "background" to "setBackground",
            "backgroundColor" to "setBackgroundColor",
            "layoutParams" to "setLayoutParams",
            "cornerRadius" to "setCornerRadius",
            "color" to "setColor",
            "style" to "setStyle",
            "strokeWidth" to "setStrokeWidth",
            "strokeCap" to "setStrokeCap",
            "strokeJoin" to "setStrokeJoin",
            "alpha" to "setAlpha",
            "antiAlias" to "setAntiAlias",
            "isAntiAlias" to "setAntiAlias",
            "letterSpacing" to "setLetterSpacing",
            "columnCount" to "setColumnCount",
            "rowCount" to "setRowCount",
            "visibility" to "setVisibility",
            "enabled" to "setEnabled",
            "title" to "setTitle",
        )

        val eqIdx = findTopLevelChar(stmt, '=')
        if (eqIdx > 0 && stmt[eqIdx - 1] !in charArrayOf('!', '=', '<', '>', '+', '-', '*', '/')) {
            val lhs = stmt.substring(0, eqIdx).trim()
            val rhs = rewriteExpression(stmt.substring(eqIdx + 1).trim().removeSuffix(";"), arrayVars, listVars, varTypes)
            val dotIdx = lhs.lastIndexOf('.')
            if (dotIdx > 0) {
                val target = lhs.substring(0, dotIdx).trim()
                val prop = lhs.substring(dotIdx + 1).trim()
                val targetType = varTypes[target].orEmpty()
                val isLayoutParamsField = targetType.contains("LayoutParams") && prop == "gravity"
                val setter = if (isLayoutParamsField) null else propMap[prop]
                if (setter != null) {
                    return "$target.$setter($rhs)"
                }
            }
            return "${rewriteExpression(lhs, arrayVars, listVars, varTypes)} = $rhs"
        }

        return rewriteExpression(stmt.removeSuffix(";"), arrayVars, listVars, varTypes)
    }

    internal fun rewriteExpression(
        expr: String,
        arrayVars: Set<String> = emptySet(),
        listVars: Set<String> = emptySet(),
        varTypes: Map<String, String> = emptyMap(),
        expectedArrayType: String? = null,
    ): String {
        var s = expr.trim()

        // 1. Anonymous `object : Type`
        s = s.replace(Regex("""\bobject\s*:\s*([A-Za-z_][\w.]*)\s*\(\s*\)""")) { m ->
            "new ${m.groupValues[1]}()"
        }
        s = s.replace(Regex("""\bobject\s*:\s*([A-Za-z_][\w.]*)""")) { m ->
            "new ${m.groupValues[1]}()"
        }

        // 2. Kotlin string templates -> Java string concatenation
        s = convertStringTemplates(s, arrayVars, listVars, varTypes)

        // 2b. `return <expr>` -> rewrite inner expression (so `return if (c) a else b` converts to ternary)
        if (s.startsWith("return ")) {
            val inner = s.removePrefix("return ").trim()
            return "return ${rewriteExpression(inner, arrayVars, listVars, varTypes, expectedArrayType)}"
        }

        // 3. Inline `if (cond) a else b` expressions (top-level or nested inside arguments/assignments)
        s = rewriteInlineIfExpressions(s, arrayVars, listVars, varTypes)

        // 4. Balanced array & list factory calls (supports nested parens like Color.parseColor(...))
        s = replaceBalancedCall(s, "intArrayOf") { args -> "new int[]{$args}" }
        s = replaceBalancedCall(s, "floatArrayOf") { args -> "new float[]{$args}" }
        s = replaceBalancedCall(s, "longArrayOf") { args -> "new long[]{$args}" }
        s = replaceBalancedCall(s, "booleanArrayOf") { args -> "new boolean[]{$args}" }
        s = replaceBalancedCall(s, "IntArray") { args -> "new int[$args]" }
        s = replaceBalancedCall(s, "FloatArray") { args -> "new float[$args]" }
        s = replaceBalancedCall(s, "LongArray") { args -> "new long[$args]" }
        s = replaceBalancedCall(s, "DoubleArray") { args -> "new double[$args]" }
        s = replaceBalancedCall(s, "BooleanArray") { args -> "new boolean[$args]" }
        s = replaceBalancedCall(s, "ByteArray") { args -> "new byte[$args]" }
        s = replaceBalancedCall(s, "arrayOf") { args ->
            val elemType = expectedArrayType?.removeSuffix("[]") ?: inferArrayLiteralElementType(args)
            "new $elemType[]{$args}"
        }
        s = s.replace(Regex("""\bmutableListOf<([^>]+)>\(\s*\)"""), "new java.util.ArrayList<$1>()")
        s = s.replace(Regex("""\barrayListOf<([^>]+)>\(\s*\)"""), "new java.util.ArrayList<$1>()")

        // 5. `matches(Regex("pat"))` or `Regex("pat").matches(x)`
        s = s.replace(Regex("""\.matches\(\s*Regex\(([^)]+)\)\s*\)"""), ".matches($1)")
        s = s.replace(Regex("""\bRegex\(([^)]+)\)\.matches\(([^)]+)\)"""), "$2.matches($1)")
        s = s.replace(".isBlank()", ".trim().isEmpty()")
        s = s.replace(".removeAt(", ".remove(")

        // 6. Android & Kotlin property getters / extensions
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.text\.toString\(\)"""), "$1.getText().toString()")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.text\b(?!\s*\()"""), "$1.getText()")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.action\b(?!\s*\()"""), "$1.getAction()")
        val xyGetterTypes = setOf("MotionEvent", "android.view.MotionEvent", "View", "android.view.View", "DragEvent")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.x\b(?!\s*\()""")) { m ->
            val target = m.groupValues[1]
            val t = varTypes[target]
            if (t in xyGetterTypes || (t == null && (target == "event" || target == "ev" || target == "motionEvent"))) {
                "$target.getX()"
            } else {
                m.value
            }
        }
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.y\b(?!\s*\()""")) { m ->
            val target = m.groupValues[1]
            val t = varTypes[target]
            if (t in xyGetterTypes || (t == null && (target == "event" || target == "ev" || target == "motionEvent"))) {
                "$target.getY()"
            } else {
                m.value
            }
        }
        s = s.replace(Regex("""(\b[A-Za-z_][\w.()]*)\.toString\(\)\.toDouble\(\)"""), "Double.parseDouble($1.toString())")
        s = replaceTrailingNumberConversion(s, ".toDouble()", "double", varTypes)
        s = replaceTrailingNumberConversion(s, ".toFloat()", "float", varTypes)
        s = replaceTrailingNumberConversion(s, ".toInt()", "int", varTypes)
        s = replaceTrailingNumberConversion(s, ".toLong()", "long", varTypes)
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.toString\(\)""")) { m ->
            val target = m.groupValues[1]
            when (varTypes[target]) {
                "int", "long", "double", "float", "boolean", "char", "byte", "short" -> "String.valueOf($target)"
                else -> "$target.toString()"
            }
        }
        s = s.replace(Regex("""\bthis@([A-Za-z_]\w*)"""), "$1.this")

        // 7. `.size` -> `.length` for arrays or `.size()` for lists, plus list[i] -> list.get(i)
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.size\b(?!\s*\()""")) { m ->
            val target = m.groupValues[1]
            if (target in arrayVars) "$target.length" else "$target.size()"
        }
        s = replaceListIndexing(s, listVars)

        // 8. Nullable operators
        s = s.replace("!!", "")
        s = s.replace("?.", ".")

        // 9. Elvis operator
        val elvisIdx = findTopLevelSeq(s, "?:")
        if (elvisIdx > 0) {
            val lhs = s.substring(0, elvisIdx).trim()
            val rhs = s.substring(elvisIdx + 2).trim()
            s = "(($lhs) != null ? ($lhs) : ($rhs))"
        }

        // 10. Bitwise Kotlin infix ops (`or`, `and`, `xor`, `shl`, `shr`, `ushr`) -> Java operators outside strings
        s = replaceKotlinBitwiseInfix(s)

        // 11. Insert `new ` before Kotlin-style constructor calls
        s = insertNewForConstructors(s)

        return s
    }

    private fun rewriteInlineIfExpressions(
        input: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): String {
        if (!input.contains("if") || !input.contains("else")) return input
        var cur = input
        var guard = 0
        while (guard++ < 12) {
            var replacedAny = false
            var i = 0
            var inStr = false
            var esc = false
            val len = cur.length
            while (i < len) {
                val c = cur[i]
                if (esc) { esc = false; i++; continue }
                if (c == '\\' && inStr) { esc = true; i++; continue }
                if (c == '"') { inStr = !inStr; i++; continue }
                if (!inStr && c == 'i' && i + 1 < len && cur[i + 1] == 'f') {
                    val prevOk = i == 0 || (!cur[i - 1].isLetterOrDigit() && cur[i - 1] != '_' && cur[i - 1] != '.')
                    var afterIf = i + 2
                    while (afterIf < len && cur[afterIf].isWhitespace()) afterIf++
                    if (prevOk && afterIf < len && cur[afterIf] == '(') {
                        val closeP = findClosingParen(cur, afterIf)
                        if (closeP > afterIf) {
                            var thenStart = closeP + 1
                            while (thenStart < len && cur[thenStart].isWhitespace()) thenStart++
                            if (thenStart < len && cur[thenStart] != '{') {
                                val (elseWordStart, elseWordEnd) = findInlineElseKeyword(cur, thenStart)
                                if (elseWordStart > thenStart) {
                                    var elseStart = elseWordEnd
                                    while (elseStart < len && cur[elseStart].isWhitespace()) elseStart++
                                    val elseEnd = findInlineElseEnd(cur, elseStart)
                                    if (elseEnd > elseStart) {
                                        val cond = rewriteExpression(cur.substring(afterIf + 1, closeP), arrayVars, listVars, varTypes)
                                        val thenPart = rewriteExpression(cur.substring(thenStart, elseWordStart).trim(), arrayVars, listVars, varTypes)
                                        val elsePart = rewriteExpression(cur.substring(elseStart, elseEnd).trim(), arrayVars, listVars, varTypes)
                                        cur = cur.substring(0, i) + "(($cond) ? ($thenPart) : ($elsePart))" + cur.substring(elseEnd)
                                        replacedAny = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                i++
            }
            if (!replacedAny) break
        }
        return cur
    }

    private fun findInlineElseKeyword(s: String, start: Int): Pair<Int, Int> {
        var i = start
        var p = 0
        var b = 0
        var inStr = false
        var esc = false
        var nestedIfs = 0
        val len = s.length
        while (i < len) {
            val c = s[i]
            if (esc) { esc = false; i++; continue }
            if (c == '\\' && inStr) { esc = true; i++; continue }
            if (c == '"') { inStr = !inStr; i++; continue }
            if (!inStr) {
                when (c) {
                    '(' -> p++
                    ')' -> { if (p == 0 && b == 0) return -1 to -1; p-- }
                    '[' -> b++
                    ']' -> { if (b == 0) return -1 to -1; b-- }
                    ',', ';' -> if (p == 0 && b == 0) return -1 to -1
                    'i' -> if (p == 0 && b == 0 && s.startsWith("if", i) &&
                        (i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] != '_') &&
                        (i + 2 >= len || (!s[i + 2].isLetterOrDigit() && s[i + 2] != '_'))
                    ) {
                        nestedIfs++
                    }
                    'e' -> if (p == 0 && b == 0 && s.startsWith("else", i) &&
                        (i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] != '_') &&
                        (i + 4 >= len || (!s[i + 4].isLetterOrDigit() && s[i + 4] != '_'))
                    ) {
                        if (nestedIfs == 0) return i to (i + 4)
                        nestedIfs--
                    }
                }
            }
            i++
        }
        return -1 to -1
    }

    private fun findInlineElseEnd(s: String, start: Int): Int {
        var i = start
        var p = 0
        var b = 0
        var inStr = false
        var esc = false
        val len = s.length
        while (i < len) {
            val c = s[i]
            if (esc) { esc = false; i++; continue }
            if (c == '\\' && inStr) { esc = true; i++; continue }
            if (c == '"') { inStr = !inStr; i++; continue }
            if (!inStr) {
                when (c) {
                    '(' -> p++
                    ')' -> { if (p == 0 && b == 0) return i; p-- }
                    '[' -> b++
                    ']' -> { if (b == 0) return i; b-- }
                    ',', ';' -> if (p == 0 && b == 0) return i
                }
            }
            i++
        }
        return len
    }

    private fun replaceKotlinBitwiseInfix(input: String): String {
        val opMap = mapOf(
            "or" to "|",
            "and" to "&",
            "xor" to "^",
            "shl" to "<<",
            "shr" to ">>",
            "ushr" to ">>>",
        )
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        var esc = false
        val len = input.length
        while (i < len) {
            val c = input[i]
            if (esc) { sb.append(c); esc = false; i++; continue }
            if (c == '\\' && inStr) { sb.append(c); esc = true; i++; continue }
            if (c == '"') { inStr = !inStr; sb.append(c); i++; continue }
            if (!inStr && c.isLetter() && (i == 0 || !input[i - 1].isLetterOrDigit() && input[i - 1] != '_' && input[i - 1] != '.')) {
                var j = i
                while (j < len && (input[j].isLetterOrDigit() || input[j] == '_')) j++
                val word = input.substring(i, j)
                val replacement = opMap[word]
                if (replacement != null && i > 0 && input[i - 1].isWhitespace() && j < len && input[j].isWhitespace()) {
                    sb.append(replacement)
                    i = j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun replaceBalancedCall(
        input: String,
        fnName: String,
        transformArgs: (String) -> String,
    ): String {
        val sb = StringBuilder()
        var i = 0
        val token = "$fnName("
        while (i < input.length) {
            val found = input.indexOf(token, i)
            if (found < 0) {
                sb.append(input.substring(i))
                break
            }
            val prevCharOk = found == 0 || !input[found - 1].isLetterOrDigit() && input[found - 1] != '_' && input[found - 1] != '.'
            if (!prevCharOk) {
                sb.append(input.substring(i, found + token.length))
                i = found + token.length
                continue
            }
            sb.append(input.substring(i, found))
            val openParen = found + fnName.length
            val closeParen = findClosingParen(input, openParen)
            val args = input.substring(openParen + 1, closeParen)
            sb.append(transformArgs(args))
            i = closeParen + 1
        }
        return sb.toString()
    }

    private fun insertNewForConstructors(input: String): String {
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        var escape = false
        val len = input.length

        while (i < len) {
            val c = input[i]
            if (escape) { sb.append(c); escape = false; i++; continue }
            if (c == '\\' && inStr) { sb.append(c); escape = true; i++; continue }
            if (c == '"') { inStr = !inStr; sb.append(c); i++; continue }
            if (inStr) { sb.append(c); i++; continue }

            if (c.isUpperCase() && (i == 0 || (!input[i - 1].isLetterOrDigit() && input[i - 1] != '_' && input[i - 1] != '.'))) {
                var j = i
                while (j < len && (input[j].isLetterOrDigit() || input[j] == '_' || input[j] == '.')) j++
                val chain = input.substring(i, j)
                var afterGeneric = j
                var genericPart = ""
                if (afterGeneric < len && input[afterGeneric] == '<') {
                    val gt = findMatchingAngle(input, afterGeneric)
                    if (gt > afterGeneric) {
                        genericPart = input.substring(afterGeneric, gt + 1)
                        afterGeneric = gt + 1
                    }
                }
                while (afterGeneric < len && input[afterGeneric] == ' ') afterGeneric++

                val lastSegment = chain.substringAfterLast('.')
                val isConstructorCall = afterGeneric < len &&
                    input[afterGeneric] == '(' &&
                    lastSegment.isNotEmpty() &&
                    lastSegment[0].isUpperCase()

                val before = sb.toString().trimEnd()
                val alreadyHasNew = before.endsWith("new") &&
                    (before.length == 3 || !before[before.length - 4].isLetterOrDigit())

                if (isConstructorCall && !alreadyHasNew && !before.endsWith("class")) {
                    sb.append("new ").append(chain).append(genericPart)
                    i = if (genericPart.isNotEmpty()) j + genericPart.length else j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun convertStringTemplates(
        input: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        var i = 0
        val len = input.length
        while (i < len) {
            if (input[i] == '"') {
                i++
                val content = StringBuilder()
                while (i < len) {
                    val ch = input[i]
                    if (ch == '\\' && i + 1 < len) {
                        content.append(ch).append(input[i + 1])
                        i += 2
                    } else if (ch == '"') {
                        i++
                        break
                    } else {
                        content.append(ch)
                        i++
                    }
                }
                val rawLit = content.toString()
                if (!rawLit.contains('$')) {
                    sb.append('"').append(rawLit).append('"')
                } else {
                    sb.append(expandTemplateLiteral(rawLit, arrayVars, listVars, varTypes))
                }
            } else {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun expandTemplateLiteral(
        raw: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): String {
        val pieces = mutableListOf<String>()
        val textBuf = StringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '$' && i + 1 < raw.length) {
                if (raw[i + 1] == '{') {
                    if (textBuf.isNotEmpty()) {
                        pieces.add("\"$textBuf\"")
                        textBuf.clear()
                    }
                    var depth = 1
                    var j = i + 2
                    while (j < raw.length && depth > 0) {
                        if (raw[j] == '{') depth++
                        else if (raw[j] == '}') depth--
                        j++
                    }
                    val innerExpr = raw.substring(i + 2, (j - 1).coerceAtLeast(i + 2))
                    pieces.add("(${rewriteExpression(innerExpr, arrayVars, listVars, varTypes)})")
                    i = j
                } else if (raw[i + 1].isLetter() || raw[i + 1] == '_') {
                    if (textBuf.isNotEmpty()) {
                        pieces.add("\"$textBuf\"")
                        textBuf.clear()
                    }
                    var j = i + 1
                    while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                    pieces.add(raw.substring(i + 1, j))
                    i = j
                } else {
                    textBuf.append(raw[i])
                    i++
                }
            } else {
                textBuf.append(raw[i])
                i++
            }
        }
        if (textBuf.isNotEmpty()) {
            pieces.add("\"$textBuf\"")
        }
        return if (pieces.size == 1) {
            val single = pieces[0]
            if (single.startsWith("\"")) single else "String.valueOf($single)"
        } else {
            "(${pieces.joinToString(" + ")})"
        }
    }

    private fun mapKotlinType(rawKtType: String): String {
        val clean = rawKtType.trim().removeSuffix("?").trim()
        KOTLIN_TO_JAVA_TYPES[clean]?.let { return it }

        val lt = clean.indexOf('<')
        if (lt > 0 && clean.endsWith(">")) {
            val base = clean.substring(0, lt).trim()
            val argRaw = clean.substring(lt + 1, clean.length - 1).trim()
            val mappedArgs = splitTopLevelComma(argRaw).joinToString(", ") { arg ->
                val m = mapKotlinType(arg)
                BOXED_TYPES[m] ?: m
            }
            return when (base) {
                "MutableList", "ArrayList" -> "java.util.ArrayList<$mappedArgs>"
                "List" -> "java.util.List<$mappedArgs>"
                "MutableMap", "HashMap" -> "java.util.HashMap<$mappedArgs>"
                "Map" -> "java.util.Map<$mappedArgs>"
                "Array" -> "$mappedArgs[]"
                else -> "$base<$mappedArgs>"
            }
        }
        return clean
    }

    private fun inferJavaType(
        rawExpr: String,
        varTypes: Map<String, String>,
        fnReturnTypes: Map<String, String> = emptyMap(),
    ): String {
        val e = rawExpr.trim()
        val callMatch = Regex("""^([A-Za-z_]\w*)\(.*\)$""").find(e)
        if (callMatch != null) {
            val knownRet = fnReturnTypes[callMatch.groupValues[1]]
            if (knownRet != null && knownRet != "void") return knownRet
        }
        val indexMatch = Regex("""^([A-Za-z_]\w*)\[.+\]$""").find(e)
        if (indexMatch != null) {
            val colType = varTypes[indexMatch.groupValues[1]]
            if (colType != null) {
                if (colType.endsWith("[]")) return colType.removeSuffix("[]")
                val lt = colType.indexOf('<')
                val gt = colType.lastIndexOf('>')
                if (lt >= 0 && gt > lt) return unboxType(colType.substring(lt + 1, gt).trim())
            }
        }
        if (e.startsWith("if ") || e.startsWith("if(")) {
            val openP = e.indexOf('(')
            val closeP = if (openP >= 0) findClosingParen(e, openP) else -1
            if (closeP > openP) {
                val afterCond = e.substring(closeP + 1).trim()
                val elseIdx = afterCond.indexOf(" else ")
                if (elseIdx > 0) {
                    val thenType = inferJavaType(afterCond.substring(0, elseIdx).trim(), varTypes, fnReturnTypes)
                    if (thenType != "Object") return thenType
                    return inferJavaType(afterCond.substring(elseIdx + 6).trim(), varTypes, fnReturnTypes)
                }
            }
        }
        return when {
            e.startsWith("object : ") || e.startsWith("object:") -> {
                e.substringAfter(':').substringBefore('{').substringBefore('(').trim().ifBlank { "Object" }
            }
            e.startsWith("\"") -> "String"
            e.startsWith("'") -> "char"
            e == "true" || e == "false" -> "boolean"
            e.startsWith("intArrayOf(") || e.startsWith("IntArray(") -> "int[]"
            e.startsWith("floatArrayOf(") || e.startsWith("FloatArray(") -> "float[]"
            e.startsWith("longArrayOf(") || e.startsWith("LongArray(") -> "long[]"
            e.startsWith("doubleArrayOf(") || e.startsWith("DoubleArray(") -> "double[]"
            e.startsWith("booleanArrayOf(") || e.startsWith("BooleanArray(") -> "boolean[]"
            e.startsWith("byteArrayOf(") || e.startsWith("ByteArray(") -> "byte[]"
            e.startsWith("arrayOf(") -> "${inferArrayLiteralElementType(e.removePrefix("arrayOf(").removeSuffix(")"))}[]"
            e.startsWith("mutableListOf<") || e.startsWith("arrayListOf<") -> {
                val arg = e.substringAfter('<').substringBefore('>')
                "java.util.ArrayList<${BOXED_TYPES[mapKotlinType(arg)] ?: mapKotlinType(arg)}>"
            }
            Regex("""^-?\d+[Ll]$""").matches(e) -> "long"
            Regex("""^-?\d+(\.\d+)?[Ff]$""").matches(e) -> "float"
            Regex("""^-?\d+\.\d+$""").matches(e) -> "double"
            Regex("""^-?\d+$""").matches(e) -> "int"
            e == "getWidth()" || e == "getHeight()" || e == "getMeasuredWidth()" || e == "getMeasuredHeight()" -> "int"
            e.startsWith("Math.max(") || e.startsWith("Math.min(") || e.startsWith("Math.abs(") -> {
                when {
                    Regex("""\b\d+(?:\.\d+)?[Ff]\b""").containsMatchIn(e) || e.contains(".toFloat()") -> "float"
                    e.contains(".") && !e.contains("Math.") -> "double"
                    else -> "int"
                }
            }
            e.startsWith("Color.parseColor") || e.startsWith("Color.") -> "int"
            e.startsWith("String.") || e.endsWith(".toString()") || e.endsWith(".trim()") ||
                e.endsWith(".lowercase()") || e.endsWith(".uppercase()") ||
                e.contains(".substring(") || e.startsWith("getString(") -> "String"
            e.startsWith("Double.parseDouble") || e.endsWith(".toDouble()") -> "double"
            e.endsWith(".toLong()") -> "long"
            e.endsWith(".toInt()") -> "int"
            e.endsWith(".toFloat()") -> "float"
            e.contains("System.currentTimeMillis()") -> "long"
            e.contains(".x") || e.contains(".y") || e.contains(".getX()") || e.contains(".getY()") -> "float"
            e.startsWith("findViewById<") -> e.substringAfter('<').substringBefore('>')
            Regex("""^[A-Z][\w.]*(?:<[^>]+>)?\(.*\)$""").matches(e) -> e.substringBefore('(').trim()
            e in varTypes -> varTypes[e]!!
            !e.contains("\"") && Regex("""\s[+\-*/%]\s""").containsMatchIn(e) -> {
                val tokens = Regex("""[A-Za-z_]\w*""").findAll(e).map { varTypes[it.value] }.toList()
                when {
                    tokens.any { it == "double" } || Regex("""\b\d+\.\d+\b(?![Ff])""").containsMatchIn(e) -> "double"
                    tokens.any { it == "float" } || Regex("""\b\d+(?:\.\d+)?[Ff]\b""").containsMatchIn(e) || e.contains("nextFloat(") -> "float"
                    tokens.any { it == "long" } || Regex("""\b\d+[Ll]\b""").containsMatchIn(e) -> "long"
                    tokens.any { it == "String" } -> "String"
                    else -> "int"
                }
            }
            else -> "Object"
        }
    }

    private fun replaceTrailingNumberConversion(
        input: String,
        suffix: String,
        javaCast: String,
        varTypes: Map<String, String>,
    ): String {
        var out = input
        var idx = out.indexOf(suffix)
        var safety = 0
        while (idx > 0 && safety++ < 25) {
            var end = idx - 1
            while (end >= 0 && out[end].isWhitespace()) end--
            var start = end
            if (end >= 0 && out[end] == ')') {
                var depth = 0
                while (start >= 0) {
                    when (out[start]) {
                        ')' -> depth++
                        '(' -> {
                            depth--
                            if (depth == 0) {
                                // include preceding identifier/method chain like Math.max(...)
                                while (start > 0 && (out[start - 1].isLetterOrDigit() || out[start - 1] == '_' || out[start - 1] == '.')) {
                                    start--
                                }
                                break
                            }
                        }
                    }
                    start--
                }
            } else {
                while (start >= 0 && (out[start].isLetterOrDigit() || out[start] == '_' || out[start] == '.' || out[start] == '(' || out[start] == ')')) {
                    if (out[start] == '(') {
                        var j = start - 1
                        while (j >= 0 && (out[j].isLetterOrDigit() || out[j] == '_' || out[j] == '.')) j--
                        start = j + 1
                    }
                    start--
                }
                start++
            }
            if (start in 0..end) {
                val target = out.substring(start, end + 1).trim()
                val replacement = if (suffix == ".toDouble()" && (varTypes[target] == "String" || target.endsWith(".getText().toString()"))) {
                    "Double.parseDouble($target)"
                } else {
                    "(($javaCast) ($target))"
                }
                out = out.substring(0, start) + replacement + out.substring(idx + suffix.length)
                idx = out.indexOf(suffix, start + replacement.length)
            } else {
                idx = out.indexOf(suffix, idx + suffix.length)
            }
        }
        return out
    }

    private fun replaceListIndexing(input: String, listVars: Set<String>): String {
        if (listVars.isEmpty()) return input
        var out = input
        for (listName in listVars) {
            val regex = Regex("""\b${Regex.escape(listName)}\[""")
            var safety = 0
            var searchFrom = 0
            while (safety++ < 30) {
                val m = regex.find(out, searchFrom) ?: break
                val openBracket = m.range.last
                var depth = 1
                var i = openBracket + 1
                while (i < out.length && depth > 0) {
                    if (out[i] == '[') depth++
                    else if (out[i] == ']') depth--
                    i++
                }
                if (depth != 0) break
                val closeBracket = i - 1
                val indexExpr = out.substring(openBracket + 1, closeBracket).trim()
                val replacement = "$listName.get($indexExpr)"
                out = out.substring(0, m.range.first) + replacement + out.substring(closeBracket + 1)
                searchFrom = m.range.first + replacement.length
            }
        }
        return out
    }

    private fun inferArrayLiteralElementType(args: String): String {
        val first = splitTopLevelComma(args).firstOrNull()?.trim().orEmpty()
        return when {
            first.startsWith("\"") -> "String"
            Regex("""^-?\d+$""").matches(first) -> "int"
            else -> "Object"
        }
    }

    private fun unboxType(t: String): String = when (t) {
        "Integer" -> "int"
        "Long" -> "long"
        "Float" -> "float"
        "Double" -> "double"
        "Boolean" -> "boolean"
        else -> t
    }

    private fun leadingIndent(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    private fun findClosingParen(s: String, openIdx: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in openIdx until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '(') depth++
                else if (c == ')') {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return s.lastIndex
    }

    private fun findMatchingAngle(s: String, openIdx: Int): Int {
        var depth = 0
        for (i in openIdx until s.length) {
            when (s[i]) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth == 0) return i
                }
                '(', ')', ';' -> return -1
            }
        }
        return -1
    }

    private fun findTopLevelChar(s: String, target: Char): Int {
        var paren = 0
        var bracket = 0
        var brace = 0
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                when (c) {
                    '(' -> paren++
                    ')' -> paren--
                    '[' -> bracket++
                    ']' -> bracket--
                    '{' -> brace++
                    '}' -> brace--
                    target -> if (paren == 0 && bracket == 0 && brace == 0) return i
                }
            }
        }
        return -1
    }

    private fun findTopLevelArrow(s: String): Int {
        var paren = 0
        var inStr = false
        var esc = false
        for (i in 0 until s.length - 1) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '(') paren++
                else if (c == ')') paren--
                else if (paren == 0 && c == '-' && s[i + 1] == '>') return i
            }
        }
        return -1
    }

    private fun findTopLevelSeq(s: String, seq: String): Int {
        var paren = 0
        var inStr = false
        var esc = false
        for (i in 0..s.length - seq.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                if (c == '(') paren++
                else if (c == ')') paren--
                else if (paren == 0 && s.startsWith(seq, i)) return i
            }
        }
        return -1
    }

    private fun splitTopLevelComma(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        var paren = 0
        var angle = 0
        var brace = 0
        var inStr = false
        var esc = false
        var start = 0
        for (i in input.indices) {
            val c = input[i]
            if (esc) { esc = false; continue }
            if (c == '\\' && inStr) { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (!inStr) {
                when (c) {
                    '(' -> paren++
                    ')' -> paren--
                    '<' -> angle++
                    '>' -> angle = (angle - 1).coerceAtLeast(0)
                    '{' -> brace++
                    '}' -> brace--
                    ',' -> if (paren == 0 && angle == 0 && brace == 0) {
                        parts.add(input.substring(start, i))
                        start = i + 1
                    }
                }
            }
        }
        parts.add(input.substring(start))
        return parts
    }
}
