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
        INTERFACE,
        ENUM_CLASS,
        OBJECT_SINGLETON,
        SYNTHETIC_KT_CLASS,
        COMPANION,
        ANON_FIELD,
        SAM_LAMBDA,
        SAM_POST_DELAYED,
        SAM_ALERT_DIALOG,
        APPLY_SCOPE,
        METHOD_OR_BLOCK,
    }

    private data class ScopeFrame(
        val kind: BlockKind,
        val className: String = "",
        val scopeTarget: String = "",
        val closingSuffix: String = "}",
    )

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

    private val VIEW_AND_PAINT_PROP_SETTERS = mapOf(
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
        "isEnabled" to "setEnabled",
        "checked" to "setChecked",
        "isChecked" to "setChecked",
        "title" to "setTitle",
        "message" to "setMessage",
        "max" to "setMax",
        "progress" to "setProgress",
        "indeterminate" to "setIndeterminate",
    )

    private val KOTLIN_MATH_FNS = setOf(
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "hypot",
        "abs", "floor", "ceil", "round", "pow", "exp", "log", "log10", "max", "min",
    )

    private val IGNORED_ANNOTATIONS = Regex(
        """@(?:Suppress|SuppressLint|RequiresApi|TargetApi|OptIn|Deprecated|JvmField|JvmStatic|JvmOverloads|Keep)\b(?:\([^)]*\))?\s*""",
    )

    private val activePropertyGetters = ThreadLocal<Set<String>>()

    fun transpile(
        kotlinSource: String,
        fallbackPackage: String = "com.example.app",
        fileName: String = "MainActivity.kt",
        runtimePackage: String = fallbackPackage,
        knownPropertyGetters: Set<String> = emptySet(),
    ): TranspileResult {
        val prevGetters = activePropertyGetters.get()
        val fileGetters = Regex("""\bval\s+([A-Za-z_]\w*)\s*:\s*[^=\n]+?\s+get\(\)\s*=""")
            .findAll(kotlinSource)
            .map { it.groupValues[1] }
            .toSet()
        activePropertyGetters.set(knownPropertyGetters + fileGetters)
        try {
            return transpileInternal(kotlinSource, fallbackPackage, fileName, runtimePackage)
        } finally {
            if (prevGetters == null) activePropertyGetters.remove() else activePropertyGetters.set(prevGetters)
        }
    }

    private fun transpileInternal(
        kotlinSource: String,
        fallbackPackage: String,
        fileName: String,
        runtimePackage: String,
    ): TranspileResult {
        val rawLines = convertTripleQuotedStrings(kotlinSource.replace("\r\n", "\n")).split('\n')
        val outLines = mutableListOf<String>()
        val lineMap = mutableMapOf<Int, Int>()

        var packageName = fallbackPackage
        val defaultClassName = fileName.substringAfterLast('/').removeSuffix(".kt").ifBlank { "MainActivity" }
        var primaryClassName = defaultClassName

        val varTypes = mutableMapOf<String, String>()
        val fnReturnTypes = mutableMapOf<String, String>()
        val arrayVars = mutableSetOf<String>()
        val listVars = mutableSetOf<String>()
        val frameStack = ArrayDeque<ScopeFrame>()
        val classStack = mutableListOf<String>()
        var hasTopLevelSyntheticClass = false
        var emittedPrimaryPublicClass = false

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

        // Pre-scan function signatures so forward calls resolve return types during var inference
        for ((_, text) in items) {
            val t = stripKotlinAnnotations(text.trim()).first
            if (isFunctionDeclaration(t)) {
                val fn = convertFunctionHeader(t, inObjectOrSynthetic = false, inInterface = false) { _, _ -> }
                val nameMatch = Regex("""fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)\s*\(""").find(t)
                if (nameMatch != null) {
                    fnReturnTypes[nameMatch.groupValues[1]] = fn.returnType
                }
            }
        }

        for ((_, text) in items) {
            val trimmed = text.trim()
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
                break
            }
        }

        var emittedHeaderImports = false
        fun ensurePackageAndRuntimeImport(lineNum: Int, alreadyEmittedPackage: Boolean) {
            if (!alreadyEmittedPackage) {
                emit("package $packageName;", lineNum)
            }
            if (!emittedHeaderImports) {
                emittedHeaderImports = true
                if (runtimePackage != packageName) {
                    emit("import $runtimePackage._KtRt;", lineNum)
                }
            }
        }

        var emittedDefaultPackage = false
        var idx = 0
        while (idx < items.size) {
            val (lineNum, rawStmtOrig) = items[idx]
            val indent = leadingIndent(rawStmtOrig)
            val trimmedOrig = rawStmtOrig.trim()

            if (trimmedOrig.isEmpty()) {
                emit("", lineNum)
                idx++
                continue
            }

            if (trimmedOrig.startsWith("//") || trimmedOrig.startsWith("/*") || trimmedOrig.startsWith("*")) {
                emit(rawStmtOrig, lineNum)
                idx++
                continue
            }

            if (trimmedOrig.startsWith("package ")) {
                val cleanPkg = trimmedOrig.removePrefix("package ").removeSuffix(";").trim()
                packageName = cleanPkg
                emit("package $cleanPkg;", lineNum)
                emittedDefaultPackage = true
                ensurePackageAndRuntimeImport(lineNum, alreadyEmittedPackage = true)
                idx++
                continue
            }

            if (!emittedDefaultPackage && classStack.isEmpty()) {
                ensurePackageAndRuntimeImport(lineNum, alreadyEmittedPackage = false)
                emittedDefaultPackage = true
            }

            if (trimmedOrig.startsWith("import ")) {
                val imp = trimmedOrig.removePrefix("import ").removeSuffix(";").trim()
                if (!imp.startsWith("kotlin.") && !imp.startsWith("kotlinx.")) {
                    emit("import $imp;", lineNum)
                }
                idx++
                continue
            }

            val (trimmed, hasVolatile) = stripKotlinAnnotations(trimmedOrig)
            if (trimmed.isEmpty()) {
                idx++
                continue
            }
            val rawStmt = "$indent$trimmed"

            // Closing brace line (`}` or `}, 16L)` or `} else {` or `} else if ...`)
            if (trimmed == "}" || (trimmed.startsWith("},") && trimmed.endsWith(")"))) {
                val trailingArgs = if (trimmed.startsWith("},")) {
                    trimmed.removePrefix("},").removeSuffix(")").trim()
                } else ""
                val frame = frameStack.removeLastOrNull()
                when (frame?.kind) {
                    BlockKind.COMPANION -> Unit
                    BlockKind.ANON_FIELD -> emit("$indent};", lineNum)
                    BlockKind.SAM_LAMBDA -> emit("$indent} });", lineNum)
                    BlockKind.SAM_POST_DELAYED -> {
                        if (trailingArgs.isNotEmpty()) {
                            val rewrittenDelay = rewriteExpression(trailingArgs, arrayVars, listVars, varTypes)
                            emit("$indent} }, $rewrittenDelay);", lineNum)
                        } else {
                            emit("$indent} });", lineNum)
                        }
                    }
                    BlockKind.SAM_ALERT_DIALOG -> emit("$indent} });", lineNum)
                    BlockKind.APPLY_SCOPE -> emit("$indent}", lineNum)
                    BlockKind.CLASS, BlockKind.INTERFACE, BlockKind.ENUM_CLASS, BlockKind.OBJECT_SINGLETON, BlockKind.SYNTHETIC_KT_CLASS -> {
                        if (classStack.isNotEmpty()) classStack.removeAt(classStack.lastIndex)
                        emit("$indent}", lineNum)
                    }
                    else -> emit("$indent}", lineNum)
                }
                idx++
                continue
            }

            if (trimmed == "} else {" || trimmed.startsWith("} else if ")) {
                frameStack.removeLastOrNull()
                frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                val rewritten = if (trimmed == "} else {") {
                    trimmed
                } else {
                    val condPart = trimmed.removePrefix("}").removeSuffix("{").trim()
                    "} ${rewriteExpression(condPart, arrayVars, listVars, varTypes)} {"
                }
                emit("$indent$rewritten", lineNum)
                idx++
                continue
            }

            // companion object {
            if (trimmed.startsWith("companion object")) {
                if (trimmed.endsWith("{")) {
                    frameStack.addLast(ScopeFrame(BlockKind.COMPANION))
                }
                emit("$indent// companion object", lineNum)
                idx++
                continue
            }

            // Top-level object / enum / interface / sealed / data class / class
            if (isClassDeclaration(trimmed)) {
                if (hasTopLevelSyntheticClass && frameStack.lastOrNull()?.kind == BlockKind.SYNTHETIC_KT_CLASS) {
                    frameStack.removeLastOrNull()
                    if (classStack.isNotEmpty()) classStack.removeAt(classStack.lastIndex)
                    emit("}", lineNum)
                    hasTopLevelSyntheticClass = false
                }

                val isTopLevel = classStack.isEmpty()
                val converted = convertClassHeader(
                    trimmed = trimmed,
                    isTopLevel = isTopLevel,
                    allowPublicTopLevel = isTopLevel && !emittedPrimaryPublicClass,
                    recordField = ::recordVarType,
                )
                if (isTopLevel && !emittedPrimaryPublicClass) {
                    primaryClassName = converted.className
                    emittedPrimaryPublicClass = true
                }
                emit("$indent${converted.headerLine}", lineNum)
                if (converted.opensBrace) {
                    classStack.add(converted.className)
                    frameStack.addLast(ScopeFrame(converted.blockKind, className = converted.className))
                }
                converted.constructorLines.forEach { cLine ->
                    emit("$indent    $cLine", lineNum)
                }
                if (!converted.opensBrace) {
                    emit("$indent}", lineNum)
                }
                idx++
                continue
            }

            // Inside ENUM_CLASS before methods/fields: convert comma-separated enum constants to trailing comma/semicolon cleanly
            val activeClassFrame = frameStack.lastOrNull {
                it.kind in setOf(BlockKind.CLASS, BlockKind.INTERFACE, BlockKind.ENUM_CLASS, BlockKind.OBJECT_SINGLETON, BlockKind.SYNTHETIC_KT_CLASS)
            }
            if (activeClassFrame?.kind == BlockKind.ENUM_CLASS &&
                frameStack.lastOrNull()?.kind == BlockKind.ENUM_CLASS &&
                !isFunctionDeclaration(trimmed) &&
                !isVariableDeclaration(trimmed) &&
                !trimmed.startsWith("constructor") &&
                !trimmed.startsWith("init")
            ) {
                val cleanEntry = trimmed.removeSuffix(";").let { entry ->
                    // Replace constructor args inside enum entry if needed e.g. RED(0xFFFF0000.toInt())
                    rewriteExpression(entry, arrayVars, listVars, varTypes)
                }
                // Check if next non-empty statement is method/field/closing brace -> add `;` instead of `,`
                val nextSignificant = (idx + 1 until items.size)
                    .map { items[it].second.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("//") }
                    .orEmpty()
                val needsSemicolon = isFunctionDeclaration(nextSignificant) ||
                    isVariableDeclaration(nextSignificant) ||
                    nextSignificant.startsWith("companion ") ||
                    nextSignificant.startsWith("init")
                val outEntry = if (needsSemicolon) {
                    cleanEntry.removeSuffix(",") + ";"
                } else {
                    cleanEntry
                }
                emit("$indent$outEntry", lineNum)
                idx++
                continue
            }

            // Lazy wrapper when file-level Kotlin functions/constants appear outside any class
            val needsSyntheticKtClass = classStack.isEmpty() &&
                (isFunctionDeclaration(trimmed) || isVariableDeclaration(trimmed))
            if (needsSyntheticKtClass) {
                primaryClassName = "${defaultClassName}Kt"
                emit("public class $primaryClassName {", lineNum)
                classStack.add(primaryClassName)
                frameStack.addLast(ScopeFrame(BlockKind.SYNTHETIC_KT_CLASS, className = primaryClassName))
                hasTopLevelSyntheticClass = true
            }

            val inObjectOrSynthetic = activeClassFrame?.kind == BlockKind.OBJECT_SINGLETON ||
                activeClassFrame?.kind == BlockKind.SYNTHETIC_KT_CLASS
            val inInterface = activeClassFrame?.kind == BlockKind.INTERFACE &&
                frameStack.lastOrNull()?.kind == BlockKind.INTERFACE

            // Secondary constructor: `constructor(...) : this(...) {` or `constructor(...) {`
            if (trimmed.startsWith("constructor") && trimmed.contains("(")) {
                val convertedCtor = convertSecondaryConstructor(
                    trimmed = trimmed,
                    className = activeClassFrame?.className ?: primaryClassName,
                    recordParam = ::recordVarType,
                )
                convertedCtor.lines.forEach { emit("$indent$it", lineNum) }
                if (convertedCtor.opensBrace) {
                    frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                }
                idx++
                continue
            }

            // Custom getter property: `val foo: Int get() = expr`
            val inlineGetterMatch = Regex("""^(?:(?:private|protected|public|internal|override)\s+)*val\s+([A-Za-z_]\w*)\s*:\s*([^=]+?)\s+get\(\)\s*=\s*(.+)$""").find(trimmed)
            if (inlineGetterMatch != null) {
                val propName = inlineGetterMatch.groupValues[1]
                val propType = mapKotlinType(inlineGetterMatch.groupValues[2].trim())
                val bodyExpr = rewriteExpression(inlineGetterMatch.groupValues[3].trim(), arrayVars, listVars, varTypes)
                val capName = propName.replaceFirstChar { it.uppercaseChar() }
                val staticPrefix = if (inObjectOrSynthetic) "static " else ""
                emit("${indent}public $staticPrefix$propType get$capName() { return $bodyExpr; }", lineNum)
                idx++
                continue
            }

            // Function declaration
            if (isFunctionDeclaration(trimmed)) {
                val fn = convertFunctionHeader(
                    trimmed = trimmed,
                    inObjectOrSynthetic = inObjectOrSynthetic,
                    inInterface = inInterface,
                    recordParam = ::recordVarType,
                )
                if (fn.isAbstractSignature) {
                    emit("$indent${fn.headerWithoutBrace};", lineNum)
                } else if (fn.singleExprBody != null) {
                    val bodyRaw = fn.singleExprBody
                    if (bodyRaw.startsWith("when") && bodyRaw.endsWith("{") && idx + 1 < items.size) {
                        val endIdx = findMatchingBlockEnd(items, idx)
                        emit("$indent${fn.headerWithoutBrace} {", lineNum)
                        val convertedWhen = convertWhenAssignmentBlock(
                            blockItems = listOf(lineNum to "${indent}    $bodyRaw") + items.subList(idx + 1, endIdx + 1),
                            assignTarget = null, // emit `return <expr>;`
                            arrayVars = arrayVars,
                            listVars = listVars,
                            varTypes = varTypes,
                        )
                        convertedWhen.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                        emit("$indent}", items[endIdx].first)
                        idx = endIdx + 1
                        continue
                    } else {
                        val rewrittenBody = rewriteExpression(bodyRaw, arrayVars, listVars, varTypes)
                        val retPrefix = if (fn.returnType == "void") "" else "return "
                        emit("$indent${fn.headerWithoutBrace} { $retPrefix$rewrittenBody; }", lineNum)
                    }
                } else {
                    emit("$indent${fn.headerWithoutBrace} {", lineNum)
                    if (fn.opensBrace) {
                        frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                    }
                }
                idx++
                continue
            }

            // `init {`
            if (trimmed.startsWith("init") && trimmed.endsWith("{")) {
                emit("$indent{", lineNum)
                frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                idx++
                continue
            }

            // `return when (...) { ... }`
            if (trimmed.startsWith("return ") && trimmed.removePrefix("return ").trim().startsWith("when") && trimmed.endsWith("{")) {
                val endIdx = findMatchingBlockEnd(items, idx)
                val whenHeader = "$indent${trimmed.removePrefix("return ").trim()}"
                val converted = convertWhenAssignmentBlock(
                    blockItems = listOf(lineNum to whenHeader) + items.subList(idx + 1, endIdx + 1),
                    assignTarget = null,
                    arrayVars = arrayVars,
                    listVars = listVars,
                    varTypes = varTypes,
                )
                converted.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                idx = endIdx + 1
                continue
            }

            // `when (...) {` or `when {` statement block
            if (trimmed.startsWith("when") && trimmed.endsWith("{")) {
                val endIdx = findMatchingBlockEnd(items, idx)
                val blockItems = items.subList(idx, endIdx + 1)
                val convertedLines = convertWhenBlock(blockItems, arrayVars, listVars, varTypes)
                convertedLines.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                idx = endIdx + 1
                continue
            }

            // `repeat(n) {` or `repeat(n) { i ->`
            val repeatMatch = Regex("""^repeat\s*\((.+)\)\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
            if (repeatMatch != null) {
                val countExpr = rewriteExpression(repeatMatch.groupValues[1].trim(), arrayVars, listVars, varTypes)
                val loopVar = repeatMatch.groupValues[2].ifBlank { "_rep$lineNum" }
                recordVarType(loopVar, "int")
                emit("${indent}for (int $loopVar = 0; $loopVar < $countExpr; $loopVar++) {", lineNum)
                frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                idx++
                continue
            }

            // `for (...) {`
            if (trimmed.startsWith("for") && trimmed.contains(" in ")) {
                val forResult = convertForHeader(trimmed, lineNum, arrayVars, listVars, varTypes, ::recordVarType)
                if (trimmed.endsWith("{")) {
                    frameStack.addLast(ScopeFrame(BlockKind.METHOD_OR_BLOCK))
                }
                emit("$indent${forResult.header}", lineNum)
                forResult.extraBodyLines.forEach { extra -> emit("$indent    $extra", lineNum) }
                idx++
                continue
            }

            // Variable / Field declaration (including `val x = when (...) {` and `val x = Type(...).apply {`)
            if (isVariableDeclaration(trimmed)) {
                val currentKind = frameStack.lastOrNull()?.kind
                val isField = classStack.isNotEmpty() &&
                    (currentKind == BlockKind.CLASS || currentKind == BlockKind.OBJECT_SINGLETON || currentKind == BlockKind.ENUM_CLASS || currentKind == BlockKind.SYNTHETIC_KT_CLASS)

                // Check `val/var name = when (...) {`
                val varWhenMatch = Regex("""^((?:(?:private|protected|public|internal|const|lateinit|override)\s+)*)(val|var)\s+([A-Za-z_]\w*)(?:\s*:\s*([^=]+?))?\s*=\s*(when\b.*\{)$""").find(trimmed)
                if (varWhenMatch != null) {
                    val varName = varWhenMatch.groupValues[3]
                    val declaredKt = varWhenMatch.groupValues[4].trim()
                    val endIdx = findMatchingBlockEnd(items, idx)
                    val jType = if (declaredKt.isNotBlank()) mapKotlinType(declaredKt) else inferWhenResultType(items.subList(idx + 1, endIdx))
                    recordVarType(varName, jType)
                    emit("$indent$jType $varName = ${defaultValueForJavaType(jType)};", lineNum)
                    val convertedWhen = convertWhenAssignmentBlock(
                        blockItems = listOf(lineNum to "$indent${varWhenMatch.groupValues[5]}") + items.subList(idx + 1, endIdx + 1),
                        assignTarget = varName,
                        arrayVars = arrayVars,
                        listVars = listVars,
                        varTypes = varTypes,
                    )
                    convertedWhen.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                    idx = endIdx + 1
                    continue
                }

                // Check `val/var name = Expr.apply {` or `.also {`
                val varApplyMatch = Regex("""^((?:(?:private|protected|public|internal|const|lateinit|override)\s+)*)(val|var)\s+([A-Za-z_]\w*)(?:\s*:\s*([^=]+?))?\s*=\s*(.+?)\.(?:apply|also)\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
                if (varApplyMatch != null) {
                    val mods = varApplyMatch.groupValues[1]
                    val keyword = varApplyMatch.groupValues[2]
                    val varName = varApplyMatch.groupValues[3]
                    val declaredKt = varApplyMatch.groupValues[4].trim()
                    val rawBaseExpr = varApplyMatch.groupValues[5].trim()
                    val jType = if (declaredKt.isNotBlank()) mapKotlinType(declaredKt) else inferJavaType(rawBaseExpr, varTypes, fnReturnTypes)
                    recordVarType(varName, jType)
                    val rewrittenBase = rewriteExpression(rawBaseExpr, arrayVars, listVars, varTypes)
                    val visPrefix = fieldVisibilityPrefix(mods, inObjectOrSynthetic)
                    val staticPrefix = if (isField && (inObjectOrSynthetic || frameStack.contains(ScopeFrame(BlockKind.COMPANION)))) "static " else ""
                    val finalPrefix = if (keyword == "val" && (!isField || !isLateinit(mods))) "final " else ""
                    emit("$indent$visPrefix$staticPrefix$finalPrefix$jType $varName = $rewrittenBase;", lineNum)
                    emit("$indent{", lineNum)
                    frameStack.addLast(ScopeFrame(BlockKind.APPLY_SCOPE, scopeTarget = varApplyMatch.groupValues[6].ifBlank { varName }))
                    idx++
                    continue
                }

                val activeApplyTarget = frameStack.lastOrNull()?.takeIf { it.kind == BlockKind.APPLY_SCOPE }?.scopeTarget
                val scopedVarRaw = if (activeApplyTarget != null && !varTypes.containsKey("context") &&
                    !trimmed.startsWith("val context") && !trimmed.startsWith("var context")
                ) {
                    rewriteApplyContextIdentifier(rawStmt, activeApplyTarget)
                } else {
                    rawStmt
                }
                val (converted, openedFrame) = convertVariableDeclaration(
                    rawLine = scopedVarRaw,
                    isField = isField,
                    inCompanionOrObject = inObjectOrSynthetic || frameStack.any { it.kind == BlockKind.COMPANION },
                    hasVolatile = hasVolatile,
                    arrayVars = arrayVars,
                    listVars = listVars,
                    varTypes = varTypes,
                    fnReturnTypes = fnReturnTypes,
                    recordVar = ::recordVarType,
                )
                if (openedFrame != null) {
                    frameStack.addLast(openedFrame)
                }
                emit(converted, lineNum)
                idx++
                continue
            }

            // `target.prop = when (...) {`
            val propWhenMatch = Regex("""^([A-Za-z_][\w.]*)\s*=\s*(when\b.*\{)$""").find(trimmed)
            if (propWhenMatch != null) {
                val lhs = propWhenMatch.groupValues[1].trim()
                val endIdx = findMatchingBlockEnd(items, idx)
                val tempVar = "_w${lineNum}"
                val inferType = inferWhenResultType(items.subList(idx + 1, endIdx))
                emit("$indent$inferType $tempVar = ${defaultValueForJavaType(inferType)};", lineNum)
                val convertedWhen = convertWhenAssignmentBlock(
                    blockItems = listOf(lineNum to "$indent${propWhenMatch.groupValues[2]}") + items.subList(idx + 1, endIdx + 1),
                    assignTarget = tempVar,
                    arrayVars = arrayVars,
                    listVars = listVars,
                    varTypes = varTypes,
                )
                convertedWhen.forEach { (jLine, kLine) -> emit(jLine, kLine) }
                val assignedStmt = rewriteStatementProperties("$lhs = $tempVar", arrayVars, listVars, varTypes)
                emit("$indent$assignedStmt;", items[endIdx].first)
                idx = endIdx + 1
                continue
            }

            // Apply current APPLY_SCOPE target if statement is inside `.apply { ... }` or `with(target) { ... }`
            val applyTarget = frameStack.lastOrNull()?.takeIf { it.kind == BlockKind.APPLY_SCOPE }?.scopeTarget
            val (convertedStmt, openedFrame) = convertGeneralStatement(
                rawLine = rawStmt,
                applyTarget = applyTarget,
                arrayVars = arrayVars,
                listVars = listVars,
                varTypes = varTypes,
                recordVar = ::recordVarType,
            )
            if (openedFrame != null) {
                frameStack.addLast(openedFrame)
            }
            emit(convertedStmt, lineNum)
            idx++
        }

        // Auto-close synthetic file-level class if opened
        while (frameStack.isNotEmpty()) {
            val popped = frameStack.removeLast()
            if (popped.kind == BlockKind.SYNTHETIC_KT_CLASS) {
                emit("}", rawLines.size.coerceAtLeast(1))
            }
        }

        return TranspileResult(
            javaSource = outLines.joinToString("\n") + "\n",
            primaryClassName = primaryClassName,
            packageName = packageName,
            lineMap = lineMap,
        )
    }

    private fun convertTripleQuotedStrings(source: String): String {
        if (!source.contains("\"\"\"")) return source
        val sb = StringBuilder()
        var i = 0
        val len = source.length
        while (i < len) {
            if (source.startsWith("\"\"\"", i)) {
                val close = source.indexOf("\"\"\"", i + 3)
                if (close > i + 3) {
                    val inner = source.substring(i + 3, close)
                    val escaped = inner
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                    sb.append('"').append(escaped).append('"')
                    i = close + 3
                    continue
                }
            }
            sb.append(source[i])
            i++
        }
        return sb.toString()
    }

    private fun stripKotlinAnnotations(trimmed: String): Pair<String, Boolean> {
        var cur = trimmed
        var hasVolatile = false
        if (cur.contains("@Volatile")) {
            hasVolatile = true
            cur = cur.replace(Regex("""@Volatile\s*"""), "")
        }
        cur = IGNORED_ANNOTATIONS.replace(cur, "").trimStart()
        return cur to hasVolatile
    }

    private fun isLateinit(mods: String): Boolean = mods.contains("lateinit")

    private fun fieldVisibilityPrefix(mods: String, inObjectOrSynthetic: Boolean): String = when {
        mods.contains("private") -> "private "
        mods.contains("protected") -> "protected "
        mods.contains("public") || inObjectOrSynthetic -> "public "
        else -> ""
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
        // Lambda headers like `repeat(3) { step ->` or `setOnClickListener { v ->` open a block body rather than continuing an expression
        if (t.endsWith("->") && countBracesOutsideStrings(t) > 0) return false
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
        return Regex("""^(?:(?:public|private|internal|protected|open|abstract|final|data|inner|sealed|enum)\s+)*(?:class|interface|object)\s+[A-Za-z_]\w*""")
            .containsMatchIn(trimmed)
    }

    private data class ConvertedClass(
        val className: String,
        val headerLine: String,
        val opensBrace: Boolean,
        val blockKind: BlockKind,
        val constructorLines: List<String>,
    )

    private fun convertClassHeader(
        trimmed: String,
        isTopLevel: Boolean,
        allowPublicTopLevel: Boolean = isTopLevel,
        recordField: (String, String) -> Unit,
    ): ConvertedClass {
        val modMatch = Regex("""^((?:(?:public|private|internal|protected|open|abstract|final|data|inner|sealed|enum)\s+)*)(class|interface|object)\s+([A-Za-z_]\w*)(.*)$""").find(trimmed)
        val rawMods = modMatch?.groupValues?.get(1)?.trim().orEmpty()
        val declKeyword = modMatch?.groupValues?.get(2) ?: "class"
        val className = modMatch?.groupValues?.get(3) ?: "MainActivity"
        var rest = modMatch?.groupValues?.get(4)?.trim().orEmpty()

        val hasOpenBrace = rest.endsWith("{")
        if (hasOpenBrace) rest = rest.dropLast(1).trim()

        val isDataClass = rawMods.contains("data")
        val isEnum = rawMods.contains("enum")
        val isInterface = declKeyword == "interface"
        val isObjectSingleton = declKeyword == "object"

        val javaVis = when {
            rawMods.contains("private") -> "private"
            rawMods.contains("protected") -> "protected"
            allowPublicTopLevel -> "public"
            isTopLevel -> ""
            else -> "private"
        }
        val extraMod = buildString {
            if (!isTopLevel && !rawMods.contains("inner") && !isEnum && !isInterface) append("static ")
            if (rawMods.contains("abstract") && !isInterface) append("abstract ")
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
                if (parenIdx >= 0 && !isInterface) {
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
            val kw = if (isInterface) "extends" else "implements"
            " $kw ${implementsList.joinToString(", ")}"
        } else ""

        val javaKindWord = when {
            isEnum -> "enum"
            isInterface -> "interface"
            else -> "class"
        }
        val blockKind = when {
            isEnum -> BlockKind.ENUM_CLASS
            isInterface -> BlockKind.INTERFACE
            isObjectSingleton -> BlockKind.OBJECT_SINGLETON
            else -> BlockKind.CLASS
        }

        val visPrefix = if (javaVis.isEmpty()) "" else "$javaVis "
        val headerLine = "$visPrefix${extraMod}$javaKindWord $className$extendsClause$implementsClause {"

        val constructorLines = mutableListOf<String>()
        if (isObjectSingleton) {
            constructorLines.add("public static final $className INSTANCE = new $className();")
        }

        if (ctorParamsRaw.isNotBlank()) {
            val params = splitTopLevelComma(ctorParamsRaw)
            data class CtorParam(val name: String, val jType: String, val isProp: Boolean, val isFinal: Boolean, val defaultExpr: String?)

            val parsedParams = params.mapNotNull { p ->
                val clean = p.trim()
                if (clean.isEmpty()) return@mapNotNull null
                val isProp = clean.startsWith("val ") || clean.startsWith("var ")
                val isFinal = clean.startsWith("val ")
                val withoutKeyword = clean.removePrefix("val ").removePrefix("var ").trim()
                val eqIdx = findTopLevelChar(withoutKeyword, '=')
                val declPart = if (eqIdx >= 0) withoutKeyword.substring(0, eqIdx).trim() else withoutKeyword
                val defaultRaw = if (eqIdx >= 0) withoutKeyword.substring(eqIdx + 1).trim() else null
                val defaultJava = defaultRaw?.let { rewriteExpression(it) }
                val name = declPart.substringBefore(':').trim()
                val ktType = declPart.substringAfter(':', "Object").trim()
                val jType = mapKotlinType(ktType)
                if (isProp) {
                    recordField(name, jType)
                    constructorLines.add("public ${if (isFinal) "final " else ""}$jType $name;")
                }
                CtorParam(name, jType, isProp, isFinal, defaultJava)
            }

            val fullArgs = parsedParams.joinToString(", ") { "${it.jType} ${it.name}" }
            val fullBody = buildList {
                if (superArgs.isNotBlank()) add("super(${rewriteExpression(superArgs)});")
                parsedParams.filter { it.isProp }.forEach { add("this.${it.name} = ${it.name};") }
            }
            val ctorVis = when {
                isEnum -> "private "
                javaVis == "private" -> ""
                else -> "public "
            }
            constructorLines.add("$ctorVis$className($fullArgs) { ${fullBody.joinToString(" ")} }")

            // Overloaded convenience constructors for trailing default values
            var arity = parsedParams.size - 1
            while (arity >= 0 && parsedParams[arity].defaultExpr != null) {
                val subset = parsedParams.take(arity)
                val subArgs = subset.joinToString(", ") { "${it.jType} ${it.name}" }
                val forwardArgs = parsedParams.mapIndexed { idxP, param ->
                    if (idxP < arity) param.name else param.defaultExpr!!
                }.joinToString(", ")
                constructorLines.add("$ctorVis$className($subArgs) { this($forwardArgs); }")
                arity--
            }

            if (isDataClass && parsedParams.any { it.isProp }) {
                val propParams = parsedParams.filter { it.isProp }
                val toStrBody = propParams.joinToString(" + \", \" + ") { "\"${it.name}=\" + ${it.name}" }
                constructorLines.add("@Override public String toString() { return \"$className(\" + $toStrBody + \")\"; }")
                val hashArgs = propParams.joinToString(", ") { it.name }
                constructorLines.add("@Override public int hashCode() { return java.util.Objects.hash($hashArgs); }")
            }
        }

        return ConvertedClass(
            className = className,
            headerLine = headerLine,
            opensBrace = hasOpenBrace,
            blockKind = blockKind,
            constructorLines = constructorLines,
        )
    }

    private data class ConvertedSecondaryCtor(
        val lines: List<String>,
        val opensBrace: Boolean,
    )

    private fun convertSecondaryConstructor(
        trimmed: String,
        className: String,
        recordParam: (String, String) -> Unit,
    ): ConvertedSecondaryCtor {
        val openIdx = trimmed.indexOf('(')
        val closeIdx = findClosingParen(trimmed, openIdx)
        val paramsRaw = if (closeIdx > openIdx) trimmed.substring(openIdx + 1, closeIdx) else ""
        var after = trimmed.substring(closeIdx + 1).trim()
        val opensBrace = after.endsWith("{")
        if (opensBrace) after = after.dropLast(1).trim()

        val javaParams = splitTopLevelComma(paramsRaw).mapNotNull { param ->
            val clean = param.trim().let {
                val eqIdx = findTopLevelChar(it, '=')
                if (eqIdx >= 0) it.substring(0, eqIdx).trim() else it
            }
            if (clean.isEmpty()) return@mapNotNull null
            val pName = clean.substringBefore(':').trim()
            val pType = mapKotlinType(clean.substringAfter(':', "Object").trim())
            recordParam(pName, pType)
            "$pType $pName"
        }

        var delegationStmt = ""
        if (after.startsWith(":")) {
            val del = after.removePrefix(":").trim()
            if (del.startsWith("this(") || del.startsWith("super(")) {
                delegationStmt = "${rewriteExpression(del)};"
            }
        }

        val lines = mutableListOf<String>()
        if (opensBrace) {
            lines.add("public $className(${javaParams.joinToString(", ")}) {")
            if (delegationStmt.isNotEmpty()) lines.add("    $delegationStmt")
        } else {
            lines.add("public $className(${javaParams.joinToString(", ")}) { $delegationStmt }")
        }
        return ConvertedSecondaryCtor(lines, opensBrace)
    }

    private fun isFunctionDeclaration(trimmed: String): Boolean {
        return Regex("""^(?:(?:override|private|protected|public|internal|open|final|abstract|suspend|inline)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_]\w*\.)?[A-Za-z_]\w*\s*\(""").containsMatchIn(trimmed)
    }

    private data class ConvertedFunction(
        val returnType: String,
        val headerWithoutBrace: String,
        val opensBrace: Boolean,
        val isAbstractSignature: Boolean = false,
        val singleExprBody: String? = null,
    )

    private fun convertFunctionHeader(
        trimmed: String,
        inObjectOrSynthetic: Boolean,
        inInterface: Boolean,
        recordParam: (String, String) -> Unit,
    ): ConvertedFunction {
        val m = Regex("""^((?:(?:override|private|protected|public|internal|open|final|abstract|suspend|inline)\s+)*)fun\s+(?:<[^>]+>\s*)?(?:([A-Za-z_]\w*)\.)?([A-Za-z_]\w*)\s*\(""").find(trimmed)!!
        val mods = m.groupValues[1]
        val receiverType = m.groupValues[2]
        val fnName = m.groupValues[3]
        val openParenIdx = m.range.last
        val closeParenIdx = findClosingParen(trimmed, openParenIdx)
        val paramsRaw = if (closeParenIdx > openParenIdx) trimmed.substring(openParenIdx + 1, closeParenIdx) else ""
        var afterParen = if (closeParenIdx + 1 < trimmed.length) trimmed.substring(closeParenIdx + 1).trim() else ""

        var opensBrace = false
        var singleExpr: String? = null
        if (afterParen.endsWith("{")) {
            opensBrace = true
            afterParen = afterParen.dropLast(1).trim()
        } else {
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

        val javaParams = mutableListOf<String>()
        if (receiverType.isNotBlank()) {
            val mappedRecv = mapKotlinType(receiverType)
            recordParam("this", mappedRecv)
            javaParams.add("$mappedRecv _receiver")
        }
        splitTopLevelComma(paramsRaw).forEach { param ->
            val eqIdx = findTopLevelChar(param.trim(), '=')
            val clean = if (eqIdx >= 0) param.trim().substring(0, eqIdx).trim() else param.trim()
            if (clean.isEmpty()) return@forEach
            val pName = clean.substringBefore(':').trim()
            val pKtType = clean.substringAfter(':', "Object").trim()
            val pJavaType = mapKotlinType(pKtType)
            recordParam(pName, pJavaType)
            javaParams.add("$pJavaType $pName")
        }

        val isOverride = mods.contains("override")
        val isAbstract = mods.contains("abstract") || (inInterface && !opensBrace && singleExpr == null)
        val vis = when {
            inInterface -> ""
            mods.contains("private") -> "private "
            mods.contains("protected") -> "protected "
            mods.contains("public") -> "public "
            isOverride && fnName in PROTECTED_OVERRIDES -> "protected "
            else -> "public "
        }
        val staticMod = if (inObjectOrSynthetic || receiverType.isNotBlank()) "static " else ""
        val abstractMod = if (mods.contains("abstract")) "abstract " else ""
        val overrideAnno = if (isOverride) "@Override " else ""

        return ConvertedFunction(
            returnType = returnJavaType,
            headerWithoutBrace = "$overrideAnno$vis$staticMod$abstractMod$returnJavaType $fnName(${javaParams.joinToString(", ")})",
            opensBrace = opensBrace,
            isAbstractSignature = isAbstract,
            singleExprBody = singleExpr,
        )
    }

    private fun isVariableDeclaration(trimmed: String): Boolean {
        return Regex("""^(?:(?:private|protected|public|internal|const|lateinit|override)\s+)*(?:val|var)\s+[A-Za-z_]\w*""").containsMatchIn(trimmed)
    }

    private fun convertVariableDeclaration(
        rawLine: String,
        isField: Boolean,
        inCompanionOrObject: Boolean,
        hasVolatile: Boolean,
        arrayVars: MutableSet<String>,
        listVars: MutableSet<String>,
        varTypes: MutableMap<String, String>,
        fnReturnTypes: Map<String, String>,
        recordVar: (String, String) -> Unit,
    ): Pair<String, ScopeFrame?> {
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

        val javaVis = if (!isField) {
            ""
        } else {
            when {
                mods.contains("private") -> "private "
                mods.contains("protected") -> "protected "
                mods.contains("public") -> "public "
                inCompanionOrObject -> "public "
                else -> ""
            }
        }
        val staticMod = if (inCompanionOrObject && isField) "static " else ""
        val volatileMod = if (hasVolatile && isField) "volatile " else ""
        val finalMod = when {
            mods.contains("const") -> "final "
            keyword == "val" && (!isField || rawInitExpr != null) -> "final "
            else -> ""
        }

        if (rawInitExpr == null) {
            return "$indent$javaVis$staticMod$volatileMod$finalMod$inferredJavaType $varName;" to null
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
            "$indent$javaVis$staticMod$volatileMod$finalMod$inferredJavaType $varName = $rewrittenInit {" to ScopeFrame(BlockKind.ANON_FIELD)
        } else {
            "$indent$javaVis$staticMod$volatileMod$finalMod$inferredJavaType $varName = $rewrittenInit;" to null
        }
    }

    private fun inferWhenResultType(branchItems: List<Pair<Int, String>>): String {
        for ((_, raw) in branchItems) {
            val t = raw.trim()
            val arrow = findTopLevelArrow(t)
            if (arrow >= 0) {
                val rhs = t.substring(arrow + 2).trim()
                if (!rhs.endsWith("{") && rhs.isNotEmpty()) {
                    return when {
                        rhs.startsWith("\"") -> "String"
                        rhs == "true" || rhs == "false" -> "boolean"
                        Regex("""^-?\d+[Ll]$""").matches(rhs) -> "long"
                        Regex("""^-?\d+(\.\d+)?[Ff]$""").matches(rhs) -> "float"
                        Regex("""^-?\d+\.\d+$""").matches(rhs) -> "double"
                        Regex("""^-?\d+$""").matches(rhs) || rhs.startsWith("Color.") -> "int"
                        else -> "Object"
                    }
                }
            }
        }
        return "Object"
    }

    private fun convertWhenAssignmentBlock(
        blockItems: List<Pair<Int, String>>,
        assignTarget: String?, // null -> emit `return ...;`
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
                        val bt = bRaw.trim()
                        if (j == branchEnd - 1 && !bt.startsWith("//") && !bt.startsWith("return ") && !bt.contains("=")) {
                            val expr = rewriteExpression(bt.removeSuffix(";"), arrayVars, listVars, varTypes)
                            val lineOut = if (assignTarget != null) "$assignTarget = $expr;" else "return $expr;"
                            output.add("${leadingIndent(bRaw)}$lineOut" to bLine)
                        } else {
                            val (stmt, _) = convertGeneralStatement(bRaw, null, arrayVars, listVars, varTypes)
                            output.add(stmt to bLine)
                        }
                    }
                    output.add("$headerIndent}" to blockItems[branchEnd].first)
                    i = branchEnd + 1
                    continue
                } else {
                    val rewrittenVal = rewriteExpression(bodyStartRaw.removeSuffix(";"), arrayVars, listVars, varTypes)
                    val assignOrRet = if (assignTarget != null) "$assignTarget = $rewrittenVal;" else "return $rewrittenVal;"
                    output.add("$branchPrefix { $assignOrRet }" to lineNum)
                }
            }
            i++
        }
        return output
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
                        val (stmt, _) = convertGeneralStatement(bRaw, null, arrayVars, listVars, varTypes)
                        output.add(stmt to bLine)
                    }
                    output.add("$headerIndent}" to blockItems[branchEnd].first)
                    i = branchEnd + 1
                    continue
                } else {
                    val (stmt, _) = convertGeneralStatement("$headerIndent    $bodyStartRaw", null, arrayVars, listVars, varTypes)
                    output.add("$branchPrefix { ${stmt.trim()} }" to lineNum)
                }
            } else {
                val (stmt, _) = convertGeneralStatement(rawLine, null, arrayVars, listVars, varTypes)
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
            val trimmedCase = rawCase.trim()
            when {
                subject != null && trimmedCase.startsWith("in ") && trimmedCase.contains("..") -> {
                    val bounds = trimmedCase.removePrefix("in ").trim().split("..", limit = 2)
                    val lo = rewriteExpression(bounds[0].trim(), arrayVars, listVars, varTypes)
                    val hi = rewriteExpression(bounds[1].trim(), arrayVars, listVars, varTypes)
                    "($subject >= $lo && $subject <= $hi)"
                }
                subject != null && trimmedCase.startsWith("in ") && trimmedCase.contains(" until ") -> {
                    val bounds = trimmedCase.removePrefix("in ").trim().split(" until ", limit = 2)
                    val lo = rewriteExpression(bounds[0].trim(), arrayVars, listVars, varTypes)
                    val hi = rewriteExpression(bounds[1].trim(), arrayVars, listVars, varTypes)
                    "($subject >= $lo && $subject < $hi)"
                }
                subject != null && trimmedCase.startsWith("is ") -> {
                    val t = mapKotlinType(trimmedCase.removePrefix("is ").trim())
                    "($subject instanceof $t)"
                }
                subject != null && trimmedCase.startsWith("!is ") -> {
                    val t = mapKotlinType(trimmedCase.removePrefix("!is ").trim())
                    "!($subject instanceof $t)"
                }
                else -> {
                    val c = rewriteExpression(trimmedCase, arrayVars, listVars, varTypes)
                    when {
                        subject == null -> c
                        c.startsWith("\"") || c.startsWith("String.") -> "$subject.equals($c)"
                        c == "null" -> "$subject == null"
                        else -> "$subject == $c"
                    }
                }
            }
        }
    }

    private data class ForConvertResult(
        val header: String,
        val extraBodyLines: List<String> = emptyList(),
    )

    private fun convertForHeader(
        trimmed: String,
        lineNum: Int,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
        recordVar: (String, String) -> Unit,
    ): ForConvertResult {
        val openIdx = trimmed.indexOf('(')
        val closeIdx = findClosingParen(trimmed, openIdx)
        val inside = trimmed.substring(openIdx + 1, closeIdx).trim()
        val hasBrace = trimmed.endsWith("{")
        val suffix = if (hasBrace) " {" else ""

        val parts = inside.split(" in ", limit = 2)
        val loopVar = parts[0].trim()
        val rawRangeOrCol = parts.getOrNull(1)?.trim().orEmpty()

        // Check `step N`
        val stepSplit = rawRangeOrCol.split(Regex("""\s+step\s+"""), limit = 2)
        val rangeOrCol = stepSplit[0].trim()
        val stepExpr = stepSplit.getOrNull(1)?.trim()?.let { rewriteExpression(it, arrayVars, listVars, varTypes) }

        // Destructured loop: `for ((idx, item) in list.withIndex())` or `for ((k, v) in map)`
        if (loopVar.startsWith("(") && loopVar.endsWith(")")) {
            val pairVars = loopVar.removeSurrounding("(", ")").split(',').map { it.trim() }
            val v1 = pairVars.getOrElse(0) { "_k" }
            val v2 = pairVars.getOrElse(1) { "_v" }
            if (rangeOrCol.endsWith(".withIndex()")) {
                val target = rangeOrCol.removeSuffix(".withIndex()").trim()
                val isArr = target in arrayVars
                val sizeExpr = if (isArr) "$target.length" else "$target.size()"
                val accessExpr = if (isArr) "$target[$v1]" else "$target.get($v1)"
                val elemType = inferElementType(target, varTypes)
                recordVar(v1, "int")
                recordVar(v2, elemType)
                return ForConvertResult(
                    header = "for (int $v1 = 0; $v1 < $sizeExpr; $v1++)$suffix",
                    extraBodyLines = listOf("final $elemType $v2 = $accessExpr;"),
                )
            } else {
                val entryName = "_entry$lineNum"
                val colExpr = rewriteExpression(rangeOrCol, arrayVars, listVars, varTypes)
                val (kType, vType) = inferMapKeyValueTypes(rangeOrCol, varTypes)
                recordVar(v1, kType)
                recordVar(v2, vType)
                return ForConvertResult(
                    header = "for (java.util.Map.Entry<$kType, $vType> $entryName : $colExpr.entrySet())$suffix",
                    extraBodyLines = listOf(
                        "final $kType $v1 = $entryName.getKey();",
                        "final $vType $v2 = $entryName.getValue();",
                    ),
                )
            }
        }

        val headerStr = when {
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
                val inc = if (stepExpr != null) "$loopVar += $stepExpr" else "$loopVar++"
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar < $end; $inc)$suffix"
            }
            rangeOrCol.contains(" downTo ") -> {
                val r = rangeOrCol.split(" downTo ", limit = 2)
                val start = rewriteExpression(r[0].trim(), arrayVars, listVars, varTypes)
                val end = rewriteExpression(r[1].trim(), arrayVars, listVars, varTypes)
                val dec = if (stepExpr != null) "$loopVar -= $stepExpr" else "$loopVar--"
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar >= $end; $dec)$suffix"
            }
            rangeOrCol.contains("..") -> {
                val r = rangeOrCol.split("..", limit = 2)
                val start = rewriteExpression(r[0].trim(), arrayVars, listVars, varTypes)
                val end = rewriteExpression(r[1].trim(), arrayVars, listVars, varTypes)
                val inc = if (stepExpr != null) "$loopVar += $stepExpr" else "$loopVar++"
                recordVar(loopVar, "int")
                "for (int $loopVar = $start; $loopVar <= $end; $inc)$suffix"
            }
            else -> {
                val colExpr = rewriteExpression(rangeOrCol, arrayVars, listVars, varTypes)
                val elemType = inferElementType(rangeOrCol, varTypes)
                recordVar(loopVar, elemType)
                "for ($elemType $loopVar : $colExpr)$suffix"
            }
        }
        return ForConvertResult(headerStr)
    }

    private fun inferMapKeyValueTypes(colExpr: String, varTypes: Map<String, String>): Pair<String, String> {
        val declared = varTypes[colExpr.trim()]
        if (declared != null) {
            val lt = declared.indexOf('<')
            val gt = declared.lastIndexOf('>')
            if (lt >= 0 && gt > lt) {
                val parts = splitTopLevelComma(declared.substring(lt + 1, gt))
                if (parts.size >= 2) return parts[0].trim() to parts[1].trim()
            }
        }
        return "Object" to "Object"
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
        applyTarget: String?,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
        recordVar: (String, String) -> Unit = { _, _ -> },
    ): Pair<String, ScopeFrame?> {
        val indent = leadingIndent(rawLine)
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed == "{" || trimmed == "}") {
            val frame = if (trimmed == "{") ScopeFrame(BlockKind.METHOD_OR_BLOCK) else null
            return rawLine to frame
        }

        // Scope function block: `target.apply {` / `target.also { v ->` / `with(target) {`
        val applyBlockMatch = Regex("""^([A-Za-z_][\w.]*)\.(?:apply|also)\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (applyBlockMatch != null) {
            val tgt = applyBlockMatch.groupValues[2].ifBlank { applyBlockMatch.groupValues[1] }
            return "$indent{" to ScopeFrame(BlockKind.APPLY_SCOPE, scopeTarget = tgt)
        }
        val withBlockMatch = Regex("""^with\s*\((.+)\)\s*\{\s*$""").find(trimmed)
        if (withBlockMatch != null) {
            val tgt = rewriteExpression(withBlockMatch.groupValues[1].trim(), arrayVars, listVars, varTypes)
            return "$indent{" to ScopeFrame(BlockKind.APPLY_SCOPE, scopeTarget = tgt)
        }

        // Single-line SAM listener: `btn.setOnClickListener { doSomething() }`
        val singleClick = Regex("""^([A-Za-z_][\w.]*)\.setOnClickListener\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?(.+)\s*\}$""").find(trimmed)
        if (singleClick != null) {
            val target = singleClick.groupValues[1]
            val param = singleClick.groupValues[2].ifBlank { "_v" }
            val body = rewriteStatementProperties(singleClick.groupValues[3].trim(), arrayVars, listVars, varTypes)
            val semi = if (body.endsWith(";")) "" else ";"
            return "$indent$target.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View $param) { $body$semi } });" to null
        }

        // Multi-line SAM listener start: `btn.setOnClickListener {` or `btn.setOnClickListener { v ->`
        val multiClick = Regex("""^([A-Za-z_][\w.]*)\.setOnClickListener\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (multiClick != null) {
            val target = multiClick.groupValues[1]
            val param = multiClick.groupValues[2].ifBlank { "_v" }
            return "$indent$target.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View $param) {" to ScopeFrame(BlockKind.SAM_LAMBDA)
        }

        // `btn.setOnLongClickListener { v ->`
        val multiLongClick = Regex("""^([A-Za-z_][\w.]*)\.setOnLongClickListener\s*\{\s*(?:([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (multiLongClick != null) {
            val target = multiLongClick.groupValues[1]
            val param = multiLongClick.groupValues[2].ifBlank { "_v" }
            return "$indent$target.setOnLongClickListener(new android.view.View.OnLongClickListener() { @Override public boolean onLongClick(android.view.View $param) {" to ScopeFrame(BlockKind.SAM_LAMBDA)
        }

        // `view.setOnTouchListener { v, ev ->`
        val multiTouch = Regex("""^([A-Za-z_][\w.]*)\.setOnTouchListener\s*\{\s*(?:([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (multiTouch != null) {
            val target = multiTouch.groupValues[1]
            val p1 = multiTouch.groupValues[2].ifBlank { "_v" }
            val p2 = multiTouch.groupValues[3].ifBlank { "_event" }
            if (p2 != "_") recordVar(p2, "MotionEvent")
            return "$indent$target.setOnTouchListener(new android.view.View.OnTouchListener() { @Override public boolean onTouch(android.view.View $p1, android.view.MotionEvent $p2) {" to ScopeFrame(BlockKind.SAM_LAMBDA)
        }

        // `switch.setOnCheckedChangeListener { btn, checked ->`
        val multiChecked = Regex("""^([A-Za-z_][\w.]*)\.setOnCheckedChangeListener\s*\{\s*(?:([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (multiChecked != null) {
            val target = multiChecked.groupValues[1]
            val p1 = multiChecked.groupValues[2].ifBlank { "_btn" }
            val p2 = multiChecked.groupValues[3].ifBlank { "_checked" }
            return "$indent$target.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() { @Override public void onCheckedChanged(android.widget.CompoundButton $p1, boolean $p2) {" to ScopeFrame(BlockKind.SAM_LAMBDA)
        }

        // `builder.setPositiveButton("OK") { _, _ ->` (or Negative/Neutral)
        val dialogBtn = Regex("""^([A-Za-z_][\w.]*)\.(setPositiveButton|setNegativeButton|setNeutralButton)\s*\(([^)]+)\)\s*\{\s*(?:([A-Za-z_]\w*)\s*,\s*([A-Za-z_]\w*)\s*->\s*)?$""").find(trimmed)
        if (dialogBtn != null) {
            val target = dialogBtn.groupValues[1]
            val method = dialogBtn.groupValues[2]
            val labelArg = rewriteExpression(dialogBtn.groupValues[3].trim(), arrayVars, listVars, varTypes)
            val p1 = dialogBtn.groupValues[4].takeIf { it.isNotBlank() && it != "_" } ?: "_dlg"
            val p2 = dialogBtn.groupValues[5].takeIf { it.isNotBlank() && it != "_" } ?: "_which"
            return "$indent$target.$method($labelArg, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface $p1, int $p2) {" to ScopeFrame(BlockKind.SAM_ALERT_DIALOG)
        }

        // Single-line `runOnUiThread { ... }` / `handler.post { ... }`
        val singleRunnable = Regex("""^(?:([A-Za-z_][\w.]*)\.)?(post|runOnUiThread)\s*\{\s*(.+)\s*\}$""").find(trimmed)
        if (singleRunnable != null) {
            val prefix = singleRunnable.groupValues[1].takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()
            val fn = singleRunnable.groupValues[2]
            val body = rewriteStatementProperties(singleRunnable.groupValues[3].trim(), arrayVars, listVars, varTypes)
            val semi = if (body.endsWith(";")) "" else ";"
            return "$indent$prefix$fn(new Runnable() { @Override public void run() { $body$semi } });" to null
        }

        // Multi-line `runOnUiThread {` or `view.post {`
        val multiRunnable = Regex("""^(?:([A-Za-z_][\w.]*)\.)?(post|runOnUiThread)\s*\{\s*$""").find(trimmed)
        if (multiRunnable != null) {
            val prefix = multiRunnable.groupValues[1].takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()
            val fn = multiRunnable.groupValues[2]
            return "$indent$prefix$fn(new Runnable() { @Override public void run() {" to ScopeFrame(BlockKind.SAM_LAMBDA)
        }

        // `Thread {` / `Thread { ... }.start()`
        val singleThreadStart = Regex("""^Thread\s*\{\s*(.+)\s*\}\s*\.start\(\)$""").find(trimmed)
        if (singleThreadStart != null) {
            val body = rewriteStatementProperties(singleThreadStart.groupValues[1].trim(), arrayVars, listVars, varTypes)
            val semi = if (body.endsWith(";")) "" else ";"
            return "${indent}new Thread(new Runnable() { @Override public void run() { $body$semi } }).start();" to null
        }

        // `postDelayed({` (without closing yet) or `postDelayed({ ... }, 16L)`
        val postDelayedStart = Regex("""^(?:([A-Za-z_][\w.]*)\.)?postDelayed\s*\(\s*\{\s*$""").find(trimmed)
        if (postDelayedStart != null) {
            val prefix = postDelayedStart.groupValues[1].takeIf { it.isNotBlank() }?.let { "$it." }.orEmpty()
            return "$indent${prefix}postDelayed(new Runnable() { @Override public void run() {" to ScopeFrame(BlockKind.SAM_POST_DELAYED, closingSuffix = "} });")
        }

        // Control headers ending with `{`
        if (trimmed.endsWith("{")) {
            val rewritten = rewriteExpression(trimmed.dropLast(1).trim(), arrayVars, listVars, varTypes)
            return "$indent$rewritten {" to ScopeFrame(BlockKind.METHOD_OR_BLOCK)
        }

        if (trimmed == "} else {" || trimmed == "else {") {
            return "$indent$trimmed" to ScopeFrame(BlockKind.METHOD_OR_BLOCK)
        }

        val baseInput = if (applyTarget != null && !varTypes.containsKey("context")) {
            rewriteApplyContextIdentifier(trimmed, applyTarget)
        } else {
            trimmed
        }
        val scopedInput = if (applyTarget != null && shouldPrefixApplyTarget(baseInput, applyTarget)) {
            prefixWithApplyTarget(baseInput, applyTarget)
        } else {
            baseInput
        }

        val rewritten = rewriteStatementProperties(scopedInput, arrayVars, listVars, varTypes)
        val needsSemi = !rewritten.endsWith(";") && !rewritten.endsWith("{") && !rewritten.endsWith("}") && !rewritten.endsWith(":")
        return (if (needsSemi) "$indent$rewritten;" else "$indent$rewritten") to null
    }

    private fun shouldPrefixApplyTarget(trimmed: String, target: String): Boolean {
        if (trimmed.startsWith("$target.") || trimmed.startsWith("this.") || trimmed.startsWith("super.")) return false
        if (trimmed.startsWith("val ") || trimmed.startsWith("var ") || trimmed.startsWith("if ") ||
            trimmed.startsWith("if(") || trimmed.startsWith("for ") || trimmed.startsWith("for(") ||
            trimmed.startsWith("while ") || trimmed.startsWith("when ") || trimmed.startsWith("return ")
        ) return false
        val eqIdx = findTopLevelChar(trimmed, '=')
        if (eqIdx > 0) {
            val lhs = trimmed.substring(0, eqIdx).trim()
            if (lhs in VIEW_AND_PAINT_PROP_SETTERS) return true
        }
        val callMatch = Regex("""^([a-z][A-Za-z0-9_]*)\(""").find(trimmed)
        if (callMatch != null) {
            val fn = callMatch.groupValues[1]
            if (fn.startsWith("set") || fn.startsWith("add") || fn == "requestFocus" || fn == "invalidate") return true
        }
        return false
    }

    private fun prefixWithApplyTarget(trimmed: String, target: String): String = "$target.$trimmed"

    private fun rewriteStatementProperties(
        stmt: String,
        arrayVars: Set<String>,
        listVars: Set<String>,
        varTypes: Map<String, String>,
    ): String {
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
                val setter = if (isLayoutParamsField) null else VIEW_AND_PAINT_PROP_SETTERS[prop]
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

        // 2b. `return <expr>`
        if (s.startsWith("return ")) {
            val inner = s.removePrefix("return ").trim()
            return "return ${rewriteExpression(inner, arrayVars, listVars, varTypes, expectedArrayType)}"
        }

        // 3. Inline `if (cond) a else b` expressions
        s = rewriteInlineIfExpressions(s, arrayVars, listVars, varTypes)

        // 4. Balanced array & Kotlin collection builders
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
        s = s.replace(Regex("""\bmutableListOf<([^>]+)>\(\s*\)""")) { m ->
            "new java.util.ArrayList<${boxGenericArgs(m.groupValues[1])}>()"
        }
        s = s.replace(Regex("""\barrayListOf<([^>]+)>\(\s*\)""")) { m ->
            "new java.util.ArrayList<${boxGenericArgs(m.groupValues[1])}>()"
        }
        s = s.replace(Regex("""\bmutableMapOf<([^>]+)>\(\s*\)""")) { m ->
            "new java.util.HashMap<${boxGenericArgs(m.groupValues[1])}>()"
        }
        s = s.replace(Regex("""\bhashMapOf<([^>]+)>\(\s*\)""")) { m ->
            "new java.util.HashMap<${boxGenericArgs(m.groupValues[1])}>()"
        }
        s = s.replace(Regex("""\bmutableSetOf<([^>]+)>\(\s*\)""")) { m ->
            "new java.util.LinkedHashSet<${boxGenericArgs(m.groupValues[1])}>()"
        }
        s = s.replace(Regex("""\b(ArrayList|HashMap|HashSet|LinkedHashSet|TreeSet|TreeMap)<([^>]+)>\(""")) { m ->
            "${m.groupValues[1]}<${boxGenericArgs(m.groupValues[2])}>("
        }
        s = s.replace(Regex("""\blistOf<[^>]+>\(\s*\)"""), "new java.util.ArrayList<>()")
        s = replaceBalancedCall(s, "listOf") { args -> "_KtRt.listOf($args)" }
        s = replaceBalancedCall(s, "mutableListOf") { args -> "_KtRt.mutableListOf($args)" }
        s = replaceBalancedCall(s, "arrayListOf") { args -> "_KtRt.mutableListOf($args)" }
        s = replaceBalancedCall(s, "setOf") { args -> "_KtRt.setOf($args)" }
        s = replaceBalancedCall(s, "mutableSetOf") { args -> "_KtRt.mutableSetOf($args)" }
        s = replaceBalancedCall(s, "mapOf") { args -> "_KtRt.mapOf(${convertInfixToPairs(args)})" }
        s = replaceBalancedCall(s, "mutableMapOf") { args -> "_KtRt.mutableMapOf(${convertInfixToPairs(args)})" }
        s = replaceBalancedCall(s, "hashMapOf") { args -> "_KtRt.mutableMapOf(${convertInfixToPairs(args)})" }
        s = replaceBalancedCall(s, "Pair") { args -> "_KtRt.pair($args)" }

        // 5. Coercion, math & Random helpers
        s = rewriteTrailingHelperCall(s, ".coerceIn", "_KtRt.coerceIn")
        s = rewriteTrailingHelperCall(s, ".coerceAtLeast", "_KtRt.coerceAtLeast")
        s = rewriteTrailingHelperCall(s, ".coerceAtMost", "_KtRt.coerceAtMost")
        s = rewriteMathAndRandomCalls(s)

        // 6. String/Regex/Collection helpers
        s = s.replace(Regex("""\.matches\(\s*Regex\(([^)]+)\)\s*\)"""), ".matches($1)")
        s = s.replace(Regex("""\bRegex\(([^)]+)\)\.matches\(([^)]+)\)"""), "$2.matches($1)")
        s = s.replace(".isBlank()", ".trim().isEmpty()")
        s = s.replace(".isNotBlank()", ".trim().length() > 0")
        s = s.replace(".lowercase()", ".toLowerCase(java.util.Locale.ROOT)")
        s = s.replace(".uppercase()", ".toUpperCase(java.util.Locale.ROOT)")
        s = s.replace(".removeAt(", ".remove(")
        s = s.replace(".appendLine(", ".append(")
        s = s.replace(".appendLine()", ".append(\"\\n\")")
        s = s.replace(".orEmpty()", "")

        // 7. Android & Kotlin property getters / extensions
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.text\.toString\(\)"""), "$1.getText().toString()")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.text\b(?!\s*\()"""), "$1.getText()")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.action\b(?!\s*\()"""), "$1.getAction()")
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.actionMasked\b(?!\s*\()"""), "$1.getActionMasked()")
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
        activePropertyGetters.get().orEmpty().forEach { prop ->
            val cap = prop.replaceFirstChar { it.uppercaseChar() }
            s = s.replace(
                Regex("""(\b[A-Za-z_][\w.()]*)\.${Regex.escape(prop)}\b(?!\s*(?:\(|=[^=]))"""),
                "$1.get$cap()",
            )
        }

        // 8. `.size` -> `.length` for arrays or `.size()` for lists, plus list[i] -> list.get(i)
        s = s.replace(Regex("""(\b[A-Za-z_]\w*)\.size\b(?!\s*\()""")) { m ->
            val target = m.groupValues[1]
            if (target in arrayVars) "$target.length" else "$target.size()"
        }
        s = replaceListIndexing(s, listVars)

        // 9. Type checks & casts: `x !is Foo`, `x is Foo`, `x as? Foo`, `x as Foo`
        s = s.replace(Regex("""(\b[A-Za-z_][\w.]*)\s+!is\s+([A-Za-z_][\w.]*)"""), "(!($1 instanceof $2))")
        s = s.replace(Regex("""(\b[A-Za-z_][\w.]*)\s+is\s+([A-Za-z_][\w.]*)"""), "($1 instanceof $2)")
        s = s.replace(Regex("""(\b[A-Za-z_][\w.]*)\s+as\?\s+([A-Za-z_][\w.]*)"""), "(($1 instanceof $2) ? ($2) ($1) : null)")
        s = s.replace(Regex("""(\b[A-Za-z_][\w.]*)\s+as\s+([A-Za-z_][\w.<>]*)"""), "(($2) ($1))")

        // 10. Nullable operators
        s = s.replace("!!", "")
        s = s.replace("?.", ".")

        // 11. Elvis operator
        val elvisIdx = findTopLevelSeq(s, "?:")
        if (elvisIdx > 0) {
            val lhs = s.substring(0, elvisIdx).trim()
            val rhs = s.substring(elvisIdx + 2).trim()
            s = "(($lhs) != null ? ($lhs) : ($rhs))"
        }

        // 12. Bitwise Kotlin infix ops (`or`, `and`, `xor`, `shl`, `shr`, `ushr`)
        s = replaceKotlinBitwiseInfix(s)

        // 13. Insert `new ` before Kotlin-style constructor calls
        s = insertNewForConstructors(s)

        return s
    }

    private fun boxGenericArgs(rawArgs: String): String =
        splitTopLevelComma(rawArgs).joinToString(", ") { arg ->
            val mapped = mapKotlinType(arg)
            BOXED_TYPES[mapped] ?: mapped
        }

    private fun convertInfixToPairs(args: String): String {
        if (args.isBlank()) return ""
        return splitTopLevelComma(args).joinToString(", ") { item ->
            val toIdx = findTopLevelSeq(item, " to ")
            if (toIdx > 0) {
                val left = item.substring(0, toIdx).trim()
                val right = item.substring(toIdx + 4).trim()
                "_KtRt.pair($left, $right)"
            } else {
                item.trim()
            }
        }
    }

    private fun rewriteTrailingHelperCall(input: String, dotMethod: String, helperTarget: String): String {
        var out = input
        var idx = out.indexOf("$dotMethod(")
        var guard = 0
        while (idx > 0 && guard++ < 25) {
            val openParen = idx + dotMethod.length
            val closeParen = findClosingParen(out, openParen)
            val receiverEnd = idx - 1
            var start = receiverEnd
            if (receiverEnd >= 0 && out[receiverEnd] == ')') {
                var depth = 0
                while (start >= 0) {
                    if (out[start] == ')') depth++
                    else if (out[start] == '(') {
                        depth--
                        if (depth == 0) {
                            while (start > 0 && (out[start - 1].isLetterOrDigit() || out[start - 1] == '_' || out[start - 1] == '.')) start--
                            break
                        }
                    }
                    start--
                }
            } else {
                while (start >= 0 && (out[start].isLetterOrDigit() || out[start] == '_' || out[start] == '.')) start--
                start++
            }
            if (start in 0..receiverEnd) {
                val receiver = out.substring(start, idx).trim()
                val args = out.substring(openParen + 1, closeParen).trim()
                val rep = if (args.isEmpty()) "$helperTarget($receiver)" else "$helperTarget($receiver, $args)"
                out = out.substring(0, start) + rep + out.substring(closeParen + 1)
                idx = out.indexOf("$dotMethod(", start + rep.length)
            } else {
                idx = out.indexOf("$dotMethod(", idx + dotMethod.length)
            }
        }
        return out
    }

    private fun rewriteMathAndRandomCalls(input: String): String {
        var out = input
        out = out.replace("Random.Default.", "Random.")
        out = out.replace("Random.nextInt(", "_KtRt.randInt(")
        out = out.replace("Random.nextFloat()", "_KtRt.randFloat()")
        out = out.replace("Random.nextDouble(", "_KtRt.randDouble(")
        out = out.replace("Random.nextBoolean()", "_KtRt.randBoolean()")
        out = out.replace(Regex("""\bkotlin\.math\.PI\b"""), "Math.PI")
        out = out.replace(Regex("""(?<![A-Za-z0-9_.])PI\b"""), "Math.PI")
        out = out.replace(Regex("""\bkotlin\.math\.([A-Za-z_]\w*\s*\()"""), "Math.$1")
        if (out.contains(".roundToInt()")) {
            out = rewriteTrailingHelperCall(out, ".roundToInt", "(int) Math.round")
        }
        // Rewrite standalone `sqrt(...)`, `sin(...)`, `cos(...)`, etc. when not preceded by `.`
        for (fn in KOTLIN_MATH_FNS) {
            out = out.replace(Regex("""(?<![A-Za-z0-9_.])\b$fn\s*\("""), "Math.$fn(")
        }
        return out
    }

    private fun defaultValueForJavaType(javaType: String): String = when (javaType) {
        "int", "short", "byte" -> "0"
        "long" -> "0L"
        "float" -> "0.0f"
        "double" -> "0.0"
        "boolean" -> "false"
        "char" -> "'\\0'"
        else -> "null"
    }

    private fun rewriteApplyContextIdentifier(text: String, applyTarget: String): String {
        if (!text.contains("context")) return text
        val sb = StringBuilder()
        var i = 0
        var inStr = false
        var esc = false
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (esc) { sb.append(c); esc = false; i++; continue }
            if (c == '\\' && inStr) { sb.append(c); esc = true; i++; continue }
            if (c == '"') { inStr = !inStr; sb.append(c); i++; continue }
            if (!inStr && text.startsWith("context", i)) {
                val prevOk = i == 0 || (!text[i - 1].isLetterOrDigit() && text[i - 1] != '_' && text[i - 1] != '.')
                val nextIdx = i + 7
                val nextOk = nextIdx >= len || (!text[nextIdx].isLetterOrDigit() && text[nextIdx] != '_')
                if (prevOk && nextOk) {
                    sb.append(applyTarget).append(".getContext()")
                    i = nextIdx
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
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

                if (isConstructorCall && !alreadyHasNew && !before.endsWith("class") && !before.endsWith("enum") && chain != "Pair") {
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
            val mappedArgs = boxGenericArgs(argRaw)
            return when (base) {
                "MutableList", "ArrayList" -> "java.util.ArrayList<$mappedArgs>"
                "List" -> "java.util.List<$mappedArgs>"
                "MutableMap", "HashMap" -> "java.util.HashMap<$mappedArgs>"
                "Map" -> "java.util.Map<$mappedArgs>"
                "MutableSet", "HashSet", "LinkedHashSet" -> "java.util.LinkedHashSet<$mappedArgs>"
                "Set" -> "java.util.Set<$mappedArgs>"
                "Pair" -> "_KtRt.Pair<$mappedArgs>"
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
            e.startsWith("mutableMapOf<") || e.startsWith("hashMapOf<") -> {
                val arg = e.substringAfter('<').substringBefore('>')
                "java.util.HashMap<${boxGenericArgs(arg)}>"
            }
            e.startsWith("mutableListOf(") || e.startsWith("arrayListOf(") -> {
                val elem = inferListCollectionElemType(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.ArrayList<$elem>"
            }
            e.startsWith("listOf(") -> {
                val elem = inferListCollectionElemType(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.List<$elem>"
            }
            e.startsWith("setOf(") -> {
                val elem = inferListCollectionElemType(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.Set<$elem>"
            }
            e.startsWith("mutableSetOf(") -> {
                val elem = inferListCollectionElemType(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.LinkedHashSet<$elem>"
            }
            e.startsWith("mapOf(") -> {
                val (kT, vT) = inferMapLiteralTypes(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.Map<$kT, $vT>"
            }
            e.startsWith("mutableMapOf(") || e.startsWith("hashMapOf(") -> {
                val (kT, vT) = inferMapLiteralTypes(e.substringAfter('(').substringBeforeLast(')'), varTypes, fnReturnTypes)
                "java.util.HashMap<$kT, $vT>"
            }
            Regex("""^-?\d+[Ll]$""").matches(e) -> "long"
            Regex("""^-?\d+(\.\d+)?[Ff]$""").matches(e) -> "float"
            Regex("""^-?\d+\.\d+$""").matches(e) -> "double"
            Regex("""^-?\d+$""").matches(e) -> "int"
            e == "getWidth()" || e == "getHeight()" || e == "getMeasuredWidth()" || e == "getMeasuredHeight()" -> "int"
            e.startsWith("Math.max(") || e.startsWith("Math.min(") || e.startsWith("Math.abs(") || e.contains(".coerceIn(") -> {
                when {
                    Regex("""\b\d+(?:\.\d+)?[Ff]\b""").containsMatchIn(e) || e.contains(".toFloat()") -> "float"
                    e.contains(".") && !e.contains("Math.") && !e.contains(".coerce") -> "double"
                    else -> "int"
                }
            }
            e.startsWith("Random.nextInt") -> "int"
            e.startsWith("Random.nextFloat") -> "float"
            e.startsWith("Random.nextDouble") -> "double"
            e.startsWith("Random.nextBoolean") -> "boolean"
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
            Regex("""^[A-Z][\w.]*(?:<[^>]+>)?\(.*\)$""").matches(e) -> mapKotlinType(e.substringBefore('(').trim())
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

    private fun inferListCollectionElemType(
        args: String,
        varTypes: Map<String, String>,
        fnReturnTypes: Map<String, String>,
    ): String {
        val first = splitTopLevelComma(args).firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return "Object"
        val raw = inferJavaType(first, varTypes, fnReturnTypes)
        return BOXED_TYPES[raw] ?: raw
    }

    private fun inferMapLiteralTypes(
        args: String,
        varTypes: Map<String, String>,
        fnReturnTypes: Map<String, String>,
    ): Pair<String, String> {
        val first = splitTopLevelComma(args).firstOrNull()?.trim().orEmpty()
        val toIdx = findTopLevelSeq(first, " to ")
        if (toIdx <= 0) return "Object" to "Object"
        val kRaw = inferJavaType(first.substring(0, toIdx).trim(), varTypes, fnReturnTypes)
        val vRaw = inferJavaType(first.substring(toIdx + 4).trim(), varTypes, fnReturnTypes)
        return (BOXED_TYPES[kRaw] ?: kRaw) to (BOXED_TYPES[vRaw] ?: vRaw)
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
                while (start >= 0) {
                    val ch = out[start]
                    if (ch == ')') {
                        var depth = 1
                        start--
                        while (start >= 0 && depth > 0) {
                            if (out[start] == ')') depth++ else if (out[start] == '(') depth--
                            start--
                        }
                        continue
                    } else if (ch == ']') {
                        var depth = 1
                        start--
                        while (start >= 0 && depth > 0) {
                            if (out[start] == ']') depth++ else if (out[start] == '[') depth--
                            start--
                        }
                        continue
                    } else if (ch.isLetterOrDigit() || ch == '_' || ch == '.') {
                        start--
                    } else {
                        break
                    }
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
