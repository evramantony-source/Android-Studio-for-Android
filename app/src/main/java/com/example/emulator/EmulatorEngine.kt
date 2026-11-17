package com.example.emulator

import androidx.compose.ui.graphics.Color
import com.example.model.ProjectFile

enum class EmulatorDeviceProfile(
    val label: String,
    val aspectWidth: Float,
    val aspectHeight: Float,
    val isTablet: Boolean = false,
) {
    PIXEL_PRO("Pixel 9 Pro", 9f, 19.5f),
    COMPACT("Compact 5.5\"", 9f, 16f),
    TABLET("Fold / Tablet", 4f, 5.2f, isTablet = true),
}

enum class EmulatorActionKind {
    NONE,
    INCREMENT,
    DECREMENT,
    RESET_COUNTER,
    CALC_KEY,
    BRUSH_COLOR,
    CLEAR_CANVAS,
    ADD_NOTE,
    TIMER_START_PAUSE,
    TIMER_LAP,
    TIMER_RESET,
    SHOW_TOAST,
    SHOW_DIALOG,
}

sealed interface EmulatorNode {
    data class ColumnContainer(
        val id: String,
        val backgroundColor: Color?,
        val centered: Boolean,
        val horizontalPaddingDp: Int,
        val children: List<EmulatorNode>,
    ) : EmulatorNode

    data class RowContainer(
        val id: String,
        val backgroundColor: Color?,
        val centered: Boolean,
        val children: List<EmulatorNode>,
    ) : EmulatorNode

    data class GridContainer(
        val id: String,
        val columns: Int,
        val children: List<EmulatorNode>,
    ) : EmulatorNode

    data class TextWidget(
        val id: String,
        val text: String,
        val textSizeSp: Float,
        val textColor: Color?,
        val isBold: Boolean,
        val isMonospace: Boolean,
        val backgroundColor: Color?,
        val borderColor: Color?,
        val cornerRadiusDp: Float,
        val bindStateKey: String? = null,
    ) : EmulatorNode

    data class ButtonWidget(
        val id: String,
        val label: String,
        val textColor: Color?,
        val backgroundColor: Color?,
        val cornerRadiusDp: Float,
        val actionKind: EmulatorActionKind,
        val actionArg: String = "",
        val weight: Float = 0f,
    ) : EmulatorNode

    data class InputWidget(
        val id: String,
        val hint: String,
        val stateKey: String,
    ) : EmulatorNode

    data class NoteListWidget(
        val id: String,
        val stateKey: String,
    ) : EmulatorNode

    data class DrawingCanvasWidget(
        val id: String,
        val defaultColor: Color,
    ) : EmulatorNode
}

data class EmulatorProgram(
    val appName: String,
    val packageName: String,
    val activityTitle: String,
    val isDarkActivity: Boolean,
    val root: EmulatorNode,
    val initialState: Map<String, String>,
    val detectedWidgetsCount: Int,
)

object EmulatorEngine {

    fun createLiveSession(
        appName: String,
        packageName: String,
        files: List<ProjectFile>,
    ): LiveVmSession? {
        val stringsMap = parseStringsXml(files.find { it.relativePath == "res/values/strings.xml" }?.content.orEmpty())
        val manifestXml = files.find { it.relativePath == "AndroidManifest.xml" }?.content.orEmpty()
        val resolvedAppName = resolveStringRef(
            extractManifestAttr(manifestXml, "label") ?: appName,
            stringsMap,
        ).ifBlank { appName }
        return LiveActivityVm.createSession(
            appName = resolvedAppName,
            packageName = packageName,
            files = files,
        )
    }

    fun parseProject(
        appName: String,
        packageName: String,
        files: List<ProjectFile>,
    ): EmulatorProgram {
        val stringsMap = parseStringsXml(files.find { it.relativePath == "res/values/strings.xml" }?.content.orEmpty())
        val colorsMap = parseColorsXml(files.find { it.relativePath == "res/values/colors.xml" }?.content.orEmpty())
        val manifestXml = files.find { it.relativePath == "AndroidManifest.xml" }?.content.orEmpty()
        val resolvedAppName = resolveStringRef(
            extractManifestAttr(manifestXml, "label") ?: appName,
            stringsMap,
        ).ifBlank { appName }

        val codeFiles = files.filter { it.relativePath.endsWith(".java") || it.relativePath.endsWith(".kt") }
        val mainSource = codeFiles.find { it.fileName == "MainActivity.kt" || it.fileName == "MainActivity.java" }
            ?: codeFiles.firstOrNull()

        if (mainSource == null) {
            return fallbackProgram(resolvedAppName, packageName, "No MainActivity.java or MainActivity.kt found in src/")
        }

        val code = mainSource.content
        val hasCanvas = code.contains("DrawingPadView")
        val isCalculator = code.contains("GridLayout") && code.contains("onKeyPress")
        val isCounter = code.contains("countDisplay") || (code.contains("count++") && code.contains("count--"))
        val isNotes = code.contains("EditText") && (code.contains("notes") || code.contains("Note"))
        val isTimer = code.contains("Chronometer") || code.contains("elapsedMs") || code.contains("lap")

        return when {
            hasCanvas -> buildCanvasProgram(resolvedAppName, packageName, code, stringsMap)
            isCalculator -> buildCalculatorProgram(resolvedAppName, packageName, code)
            isCounter -> buildCounterProgram(resolvedAppName, packageName, code, stringsMap)
            isNotes -> buildNotesProgram(resolvedAppName, packageName, code, stringsMap)
            isTimer -> buildTimerProgram(resolvedAppName, packageName, code, stringsMap)
            else -> buildGenericJavaActivityProgram(resolvedAppName, packageName, code, stringsMap, colorsMap)
        }
    }

    private fun buildCounterProgram(
        appName: String,
        packageName: String,
        code: String,
        strings: Map<String, String>,
    ): EmulatorProgram {
        val bgHex = extractRootBgHex(code) ?: "#0F172A"
        val titleText = extractTextAssignment(code, "title", strings) ?: appName
        val subtitleText = extractTextAssignment(code, "subtitle", strings)
            ?: strings["welcome_subtitle"]
            ?: "Built with DroidForge Studio"

        val root = EmulatorNode.ColumnContainer(
            id = "root",
            backgroundColor = parseColorSafe(bgHex),
            centered = true,
            horizontalPaddingDp = 20,
            children = listOf(
                EmulatorNode.TextWidget(
                    id = "title",
                    text = titleText,
                    textSizeSp = 22f,
                    textColor = Color(0xFFF8FAFC),
                    isBold = true,
                    isMonospace = false,
                    backgroundColor = null,
                    borderColor = null,
                    cornerRadiusDp = 0f,
                ),
                EmulatorNode.TextWidget(
                    id = "subtitle",
                    text = subtitleText,
                    textSizeSp = 12f,
                    textColor = Color(0xFF94A3B8),
                    isBold = false,
                    isMonospace = false,
                    backgroundColor = null,
                    borderColor = null,
                    cornerRadiusDp = 0f,
                ),
                EmulatorNode.TextWidget(
                    id = "countDisplay",
                    text = "0",
                    textSizeSp = 52f,
                    textColor = Color(0xFF38BDF8),
                    isBold = true,
                    isMonospace = true,
                    backgroundColor = Color(0xFF1E293B),
                    borderColor = Color(0xFF334155),
                    cornerRadiusDp = 16f,
                    bindStateKey = "count",
                ),
                EmulatorNode.RowContainer(
                    id = "controls",
                    backgroundColor = null,
                    centered = true,
                    children = listOf(
                        EmulatorNode.ButtonWidget(
                            id = "minusBtn",
                            label = "-1",
                            textColor = Color.White,
                            backgroundColor = Color(0xFF334155),
                            cornerRadiusDp = 10f,
                            actionKind = EmulatorActionKind.DECREMENT,
                        ),
                        EmulatorNode.ButtonWidget(
                            id = "resetBtn",
                            label = "Reset",
                            textColor = Color.White,
                            backgroundColor = Color(0xFF475569),
                            cornerRadiusDp = 10f,
                            actionKind = EmulatorActionKind.RESET_COUNTER,
                            actionArg = "Counter reset",
                        ),
                        EmulatorNode.ButtonWidget(
                            id = "plusBtn",
                            label = "+1",
                            textColor = Color.White,
                            backgroundColor = Color(0xFF0284C7),
                            cornerRadiusDp = 10f,
                            actionKind = EmulatorActionKind.INCREMENT,
                        ),
                    ),
                ),
            ),
        )
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = root,
            initialState = mapOf("count" to "0"),
            detectedWidgetsCount = 6,
        )
    }

    private fun buildCanvasProgram(
        appName: String,
        packageName: String,
        code: String,
        strings: Map<String, String>,
    ): EmulatorProgram {
        val root = EmulatorNode.ColumnContainer(
            id = "root",
            backgroundColor = Color(0xFF0B0F17),
            centered = false,
            horizontalPaddingDp = 0,
            children = listOf(
                EmulatorNode.DrawingCanvasWidget(
                    id = "pad",
                    defaultColor = Color(0xFF38BDF8),
                ),
                EmulatorNode.RowContainer(
                    id = "toolbar",
                    backgroundColor = Color(0xFF182030),
                    centered = true,
                    children = listOf(
                        EmulatorNode.ButtonWidget("c1", "Cyan", Color.Black, Color(0xFF38BDF8), 8f, EmulatorActionKind.BRUSH_COLOR, "#38BDF8", weight = 1f),
                        EmulatorNode.ButtonWidget("c2", "Mint", Color.Black, Color(0xFF34D399), 8f, EmulatorActionKind.BRUSH_COLOR, "#34D399", weight = 1f),
                        EmulatorNode.ButtonWidget("c3", "Gold", Color.Black, Color(0xFFFBBF24), 8f, EmulatorActionKind.BRUSH_COLOR, "#FBBF24", weight = 1f),
                        EmulatorNode.ButtonWidget("c4", "Coral", Color.Black, Color(0xFFF87171), 8f, EmulatorActionKind.BRUSH_COLOR, "#F87171", weight = 1f),
                        EmulatorNode.ButtonWidget("clr", "Clear", Color.White, Color(0xFF334155), 8f, EmulatorActionKind.CLEAR_CANVAS, weight = 1f),
                    ),
                ),
            ),
        )
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = root,
            initialState = mapOf("brushColor" to "#38BDF8"),
            detectedWidgetsCount = 6,
        )
    }

    private fun buildCalculatorProgram(
        appName: String,
        packageName: String,
        code: String,
    ): EmulatorProgram {
        val keys = listOf(
            "7", "8", "9", "/",
            "4", "5", "6", "*",
            "1", "2", "3", "-",
            "C", "0", "=", "+",
        )
        val buttons = keys.mapIndexed { idx, key ->
            val isDigit = key.length == 1 && key[0].isDigit()
            EmulatorNode.ButtonWidget(
                id = "calc_$idx",
                label = key,
                textColor = Color.White,
                backgroundColor = if (isDigit) Color(0xFF1E293B) else Color(0xFF0284C7),
                cornerRadiusDp = 10f,
                actionKind = EmulatorActionKind.CALC_KEY,
                actionArg = key,
            )
        }
        val root = EmulatorNode.ColumnContainer(
            id = "root",
            backgroundColor = Color(0xFF0B0F17),
            centered = false,
            horizontalPaddingDp = 12,
            children = listOf(
                EmulatorNode.TextWidget(
                    id = "calcDisplay",
                    text = "0",
                    textSizeSp = 38f,
                    textColor = Color.White,
                    isBold = true,
                    isMonospace = true,
                    backgroundColor = Color(0xFF111827),
                    borderColor = Color(0xFF1F2937),
                    cornerRadiusDp = 12f,
                    bindStateKey = "calcDisplay",
                ),
                EmulatorNode.GridContainer(
                    id = "grid",
                    columns = 4,
                    children = buttons,
                ),
            ),
        )
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = root,
            initialState = mapOf(
                "calcDisplay" to "0",
                "calcFirst" to "0",
                "calcOp" to "",
                "calcClearNext" to "true",
            ),
            detectedWidgetsCount = 17,
        )
    }

    private fun buildNotesProgram(
        appName: String,
        packageName: String,
        code: String,
        strings: Map<String, String>,
    ): EmulatorProgram {
        val root = EmulatorNode.ColumnContainer(
            id = "root",
            backgroundColor = Color(0xFF0F172A),
            centered = false,
            horizontalPaddingDp = 14,
            children = listOf(
                EmulatorNode.TextWidget(
                    id = "notesHeader",
                    text = appName,
                    textSizeSp = 20f,
                    textColor = Color(0xFFF8FAFC),
                    isBold = true,
                    isMonospace = false,
                    backgroundColor = null,
                    borderColor = null,
                    cornerRadiusDp = 0f,
                ),
                EmulatorNode.InputWidget(
                    id = "noteInput",
                    hint = "Write a quick note...",
                    stateKey = "noteInput",
                ),
                EmulatorNode.RowContainer(
                    id = "noteActions",
                    backgroundColor = null,
                    centered = false,
                    children = listOf(
                        EmulatorNode.ButtonWidget(
                            id = "addNoteBtn",
                            label = "Save Note",
                            textColor = Color.White,
                            backgroundColor = Color(0xFF0284C7),
                            cornerRadiusDp = 10f,
                            actionKind = EmulatorActionKind.ADD_NOTE,
                            weight = 1f,
                        ),
                    ),
                ),
                EmulatorNode.NoteListWidget(
                    id = "notesList",
                    stateKey = "notesList",
                ),
            ),
        )
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = root,
            initialState = mapOf(
                "noteInput" to "",
                "notesList" to "Tap Save Note to capture ideas on-device",
            ),
            detectedWidgetsCount = 4,
        )
    }

    private fun buildTimerProgram(
        appName: String,
        packageName: String,
        code: String,
        strings: Map<String, String>,
    ): EmulatorProgram {
        val root = EmulatorNode.ColumnContainer(
            id = "root",
            backgroundColor = Color(0xFF0B0F17),
            centered = true,
            horizontalPaddingDp = 18,
            children = listOf(
                EmulatorNode.TextWidget(
                    id = "timerTitle",
                    text = appName,
                    textSizeSp = 20f,
                    textColor = Color(0xFFF8FAFC),
                    isBold = true,
                    isMonospace = false,
                    backgroundColor = null,
                    borderColor = null,
                    cornerRadiusDp = 0f,
                ),
                EmulatorNode.TextWidget(
                    id = "timerReadout",
                    text = "00:00.0",
                    textSizeSp = 44f,
                    textColor = Color(0xFF34D399),
                    isBold = true,
                    isMonospace = true,
                    backgroundColor = Color(0xFF111827),
                    borderColor = Color(0xFF1F2937),
                    cornerRadiusDp = 16f,
                    bindStateKey = "timerDisplay",
                ),
                EmulatorNode.RowContainer(
                    id = "timerControls",
                    backgroundColor = null,
                    centered = true,
                    children = listOf(
                        EmulatorNode.ButtonWidget("t_toggle", "Start / Pause", Color.Black, Color(0xFF34D399), 10f, EmulatorActionKind.TIMER_START_PAUSE),
                        EmulatorNode.ButtonWidget("t_lap", "Lap", Color.White, Color(0xFF0284C7), 10f, EmulatorActionKind.TIMER_LAP),
                        EmulatorNode.ButtonWidget("t_reset", "Reset", Color.White, Color(0xFF475569), 10f, EmulatorActionKind.TIMER_RESET),
                    ),
                ),
                EmulatorNode.NoteListWidget(
                    id = "lapList",
                    stateKey = "laps",
                ),
            ),
        )
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = root,
            initialState = mapOf(
                "timerDisplay" to "00:00.0",
                "timerRunning" to "false",
                "timerTenths" to "0",
                "laps" to "",
            ),
            detectedWidgetsCount = 6,
        )
    }

    private fun buildGenericJavaActivityProgram(
        appName: String,
        packageName: String,
        code: String,
        strings: Map<String, String>,
        colors: Map<String, Color>,
    ): EmulatorProgram {
        val bgHex = extractRootBgHex(code)
        val rootColor = bgHex?.let { parseColorSafe(it) } ?: colors["surface"] ?: Color(0xFF111722)
        val isDark = isDarkColor(rootColor)

        val widgets = mutableListOf<EmulatorNode>()

        // Parse TextView.setText(...) and Kotlin var.text = ... expressions
        val textRegex = Regex("""(\w+)\.setText\(([^)]+)\)|(\w+)\.text\s*=\s*([^\n;]+)""")
        val seenTexts = linkedSetOf<String>()
        textRegex.findAll(code).forEachIndexed { idx, match ->
            val varName = match.groupValues[1].ifBlank { match.groupValues[3] }
            val arg = match.groupValues[2].ifBlank { match.groupValues[4] }.trim()
            val resolved = evaluateJavaStringExpr(arg, appName, strings)
            if (resolved.isNotBlank() && seenTexts.add("$varName:$resolved")) {
                val isBtn = code.contains(Regex("""Button\s+$varName\b|\bval\s+$varName\s*=\s*Button\b|\bvar\s+$varName\s*=\s*Button\b"""))
                if (isBtn) {
                    val hasDialog = code.contains("AlertDialog")
                    val toastMatch = Regex("""Toast\.makeText\([^,]+,\s*"([^"]+)"""").find(code)
                    widgets.add(
                        EmulatorNode.ButtonWidget(
                            id = "btn_$idx",
                            label = resolved,
                            textColor = Color.White,
                            backgroundColor = Color(0xFF0284C7),
                            cornerRadiusDp = 10f,
                            actionKind = when {
                                hasDialog -> EmulatorActionKind.SHOW_DIALOG
                                toastMatch != null -> EmulatorActionKind.SHOW_TOAST
                                else -> EmulatorActionKind.SHOW_TOAST
                            },
                            actionArg = toastMatch?.groupValues?.get(1) ?: "Clicked '$resolved'",
                        ),
                    )
                } else {
                    widgets.add(
                        EmulatorNode.TextWidget(
                            id = "tv_$idx",
                            text = resolved,
                            textSizeSp = if (idx == 0) 20f else 15f,
                            textColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A),
                            isBold = idx == 0,
                            isMonospace = false,
                            backgroundColor = null,
                            borderColor = null,
                            cornerRadiusDp = 0f,
                        ),
                    )
                }
            }
        }

        if (widgets.isEmpty()) {
            widgets.add(
                EmulatorNode.TextWidget(
                    id = "fallback_tv",
                    text = "Hello from $appName!",
                    textSizeSp = 20f,
                    textColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF0F172A),
                    isBold = true,
                    isMonospace = false,
                    backgroundColor = null,
                    borderColor = null,
                    cornerRadiusDp = 0f,
                ),
            )
        }

        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = isDark,
            root = EmulatorNode.ColumnContainer(
                id = "root",
                backgroundColor = rootColor,
                centered = true,
                horizontalPaddingDp = 18,
                children = widgets,
            ),
            initialState = emptyMap(),
            detectedWidgetsCount = widgets.size,
        )
    }

    private fun fallbackProgram(appName: String, packageName: String, message: String): EmulatorProgram {
        return EmulatorProgram(
            appName = appName,
            packageName = packageName,
            activityTitle = appName,
            isDarkActivity = true,
            root = EmulatorNode.ColumnContainer(
                id = "root",
                backgroundColor = Color(0xFF0F172A),
                centered = true,
                horizontalPaddingDp = 18,
                children = listOf(
                    EmulatorNode.TextWidget(
                        id = "msg",
                        text = message,
                        textSizeSp = 15f,
                        textColor = Color(0xFFF87171),
                        isBold = false,
                        isMonospace = true,
                        backgroundColor = null,
                        borderColor = null,
                        cornerRadiusDp = 0f,
                    ),
                ),
            ),
            initialState = emptyMap(),
            detectedWidgetsCount = 1,
        )
    }

    private fun parseStringsXml(xml: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val regex = Regex("""<string\s+name="([^"]+)">([\s\S]*?)</string>""")
        regex.findAll(xml).forEach { m ->
            map[m.groupValues[1].trim()] = m.groupValues[2].trim()
        }
        return map
    }

    private fun parseColorsXml(xml: String): Map<String, Color> {
        val map = linkedMapOf<String, Color>()
        val regex = Regex("""<color\s+name="([^"]+)">([^<]+)</color>""")
        regex.findAll(xml).forEach { m ->
            parseColorSafe(m.groupValues[2].trim())?.let { c ->
                map[m.groupValues[1].trim()] = c
            }
        }
        return map
    }

    private fun extractManifestAttr(xml: String, attr: String): String? {
        return Regex("""android:$attr\s*=\s*"([^"]+)"""").find(xml)?.groupValues?.get(1)
    }

    private fun resolveStringRef(raw: String, strings: Map<String, String>): String {
        return if (raw.startsWith("@string/")) {
            strings[raw.removePrefix("@string/")] ?: raw
        } else {
            raw
        }
    }

    private fun extractRootBgHex(code: String): String? {
        return Regex("""setBackgroundColor\(\s*Color\.parseColor\(\s*"(#[0-9A-Fa-f]{6,8})"\s*\)\s*\)""")
            .find(code)?.groupValues?.get(1)
    }

    private fun extractTextAssignment(code: String, varName: String, strings: Map<String, String>): String? {
        val m = Regex("""\b$varName\.setText\(([^)]+)\)|\b$varName\.text\s*=\s*([^\n;]+)""").find(code) ?: return null
        val expr = m.groupValues[1].ifBlank { m.groupValues[2] }
        return evaluateJavaStringExpr(expr, strings["app_name"] ?: "App", strings)
    }

    private fun evaluateJavaStringExpr(expr: String, appName: String, strings: Map<String, String>): String {
        val tokens = expr.split('+').map { it.trim() }
        val sb = StringBuilder()
        for (tok in tokens) {
            when {
                tok.startsWith("\"") && tok.endsWith("\"") && tok.length >= 2 ->
                    sb.append(tok.substring(1, tok.length - 1))
                tok.startsWith("getString(R.string.") && tok.endsWith(")") -> {
                    val key = tok.removePrefix("getString(R.string.").removeSuffix(")").trim()
                    sb.append(strings[key] ?: appName)
                }
            }
        }
        return sb.toString().trim()
    }

    fun parseColorSafe(hex: String): Color? {
        val clean = hex.trim().removePrefix("#")
        return runCatching {
            when (clean.length) {
                6 -> Color((0xFF000000L or clean.toLong(16)).toInt())
                8 -> Color(clean.toLong(16).toInt())
                else -> null
            }
        }.getOrNull()
    }

    private fun isDarkColor(color: Color): Boolean {
        val luma = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        return luma < 0.5f
    }
}
