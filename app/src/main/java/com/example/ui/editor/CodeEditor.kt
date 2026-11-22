package com.example.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BuildDiagnostic
import com.example.model.DiagnosticSeverity
import com.example.model.EditorSettings
import com.example.model.FileLanguage
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalSyntaxColors

@Composable
fun CodeEditor(
    filePath: String,
    content: String,
    language: FileLanguage,
    settings: EditorSettings,
    diagnostics: List<BuildDiagnostic>,
    jumpToLineRequest: Pair<String, Int>?,
    onJumpHandled: () -> Unit,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRequestFixWithAi: (() -> Unit)? = null,
) {
    val syntaxColors = LocalSyntaxColors.current
    var textValue by remember(filePath) {
        mutableStateOf(TextFieldValue(content))
    }
    val undoStack = remember(filePath) { mutableListOf<TextFieldValue>() }
    val redoStack = remember(filePath) { mutableListOf<TextFieldValue>() }

    // Keep editor synchronized when external actions modify the file
    LaunchedEffect(content) {
        if (content != textValue.text) {
            undoStack.add(textValue)
            if (undoStack.size > 60) undoStack.removeAt(0)
            redoStack.clear()
            textValue = TextFieldValue(
                text = content,
                selection = TextRange(content.length.coerceAtMost(textValue.selection.start)),
            )
        }
    }

    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var useRegexFind by remember { mutableStateOf(false) }
    var showGoToLineBar by remember { mutableStateOf(false) }
    var goToLineInput by remember { mutableStateOf("") }

    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    LaunchedEffect(filePath) {
        verticalScroll.scrollTo(0)
        horizontalScroll.scrollTo(0)
    }

    val lineCount = remember(textValue.text) {
        if (textValue.text.isEmpty()) 1 else textValue.text.count { it == '\n' } + 1
    }
    val errorLineNumbers = remember(diagnostics, filePath) {
        diagnostics
            .filter { it.filePath == filePath && it.severity == DiagnosticSeverity.ERROR }
            .map { it.line }
            .toSet()
    }
    val currentLine = remember(textValue.text, textValue.selection) {
        val cursor = textValue.selection.start.coerceIn(0, textValue.text.length)
        textValue.text.substring(0, cursor).count { it == '\n' } + 1
    }

    val lineHeightSp = (settings.fontSizeSp + 7).sp
    var gutterLayout by remember(filePath) { mutableStateOf<TextLayoutResult?>(null) }

    // Handle clicking a build error diagnostic to jump to line
    LaunchedEffect(jumpToLineRequest) {
        if (jumpToLineRequest != null && jumpToLineRequest.first == filePath) {
            val targetLine = jumpToLineRequest.second.coerceAtLeast(1)
            val offset = lineStartOffset(textValue.text, targetLine)
            textValue = textValue.copy(selection = TextRange(offset))
            val approxY = gutterLayout?.let { layout ->
                val idx = (targetLine - 1).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
                layout.getLineTop(idx).toInt()
            } ?: (((targetLine - 3).coerceAtLeast(0)) * (settings.fontSizeSp + 7) * 2)
            verticalScroll.animateScrollTo(approxY.coerceAtLeast(0))
            onJumpHandled()
        }
    }

    fun applyEdit(newValue: TextFieldValue) {
        if (newValue.text != textValue.text) {
            undoStack.add(textValue)
            if (undoStack.size > 60) undoStack.removeAt(0)
            redoStack.clear()
            onContentChange(newValue.text)
        }
        textValue = newValue
    }

    fun insertSnippet(snippet: String, cursorBackOffset: Int = 0) {
        val start = textValue.selection.min.coerceIn(0, textValue.text.length)
        val end = textValue.selection.max.coerceIn(0, textValue.text.length)
        val nextText = textValue.text.replaceRange(start, end, snippet)
        val nextCursor = (start + snippet.length - cursorBackOffset).coerceIn(0, nextText.length)
        applyEdit(TextFieldValue(nextText, TextRange(nextCursor)))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(syntaxColors.editorBackground),
    ) {
        // Collapsible Find & Replace bar (supports Plain Text & Regex)
        AnimatedVisibility(visible = showFindBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = { findQuery = it },
                            placeholder = {
                                Text(
                                    if (useRegexFind) "Regex pattern..." else "Find...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("editor_find_input"),
                        )
                        FilterChip(
                            selected = useRegexFind,
                            onClick = { useRegexFind = !useRegexFind },
                            label = { Text(".*", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("editor_regex_toggle"),
                        )
                        val matchCount = remember(findQuery, useRegexFind, textValue.text) {
                            if (findQuery.isEmpty()) 0
                            else runCatching {
                                val rx = if (useRegexFind) Regex(findQuery) else Regex(Regex.escape(findQuery))
                                rx.findAll(textValue.text).count()
                            }.getOrDefault(0)
                        }
                        Text(
                            text = "$matchCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val from = textValue.selection.max.coerceIn(0, textValue.text.length)
                                    val rx = runCatching {
                                        if (useRegexFind) Regex(findQuery) else Regex(Regex.escape(findQuery))
                                    }.getOrNull()
                                    val hit = rx?.find(textValue.text, from) ?: rx?.find(textValue.text, 0)
                                    if (hit != null) {
                                        textValue = textValue.copy(
                                            selection = TextRange(hit.range.first, hit.range.last + 1),
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("Next")
                        }
                        IconButton(onClick = { showFindBar = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close find bar")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            placeholder = { Text("Replace with...", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                if (findQuery.isNotEmpty()) {
                                    val replaced = runCatching {
                                        if (useRegexFind) {
                                            Regex(findQuery).replace(textValue.text, replaceQuery)
                                        } else {
                                            textValue.text.replace(findQuery, replaceQuery)
                                        }
                                    }.getOrNull()
                                    if (replaced != null) {
                                        applyEdit(TextFieldValue(replaced, TextRange(0)))
                                    }
                                }
                            },
                        ) {
                            Text("Replace All")
                        }
                    }
                }
            }
        }

        // Collapsible Go-to-Line bar
        AnimatedVisibility(visible = showGoToLineBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = goToLineInput,
                        onValueChange = { goToLineInput = it.filter { ch -> ch.isDigit() } },
                        placeholder = { Text("Line (1-$lineCount)", style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("editor_goto_line_input"),
                    )
                    TextButton(
                        onClick = {
                            val target = goToLineInput.toIntOrNull()?.coerceIn(1, lineCount) ?: return@TextButton
                            val offset = lineStartOffset(textValue.text, target)
                            textValue = textValue.copy(selection = TextRange(offset))
                            showGoToLineBar = false
                        },
                        modifier = Modifier.testTag("editor_goto_line_confirm"),
                    ) {
                        Text("Jump")
                    }
                    IconButton(onClick = { showGoToLineBar = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close go-to-line bar")
                    }
                }
            }
        }

        // Main Gutter + Code Canvas
        val codeTextStyle = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = settings.fontSizeSp.sp,
            lineHeight = lineHeightSp,
            color = syntaxColors.plainText,
        )

        val visualTransformation = remember(language, syntaxColors, filePath) {
            CodeSyntaxVisualTransformation(language, syntaxColors, filePath)
        }

        val errorDotColor = MaterialTheme.colorScheme.error
        val lineNumbersText = remember(lineCount) {
            buildString(lineCount * 4) {
                for (n in 1..lineCount) {
                    if (n > 1) append('\n')
                    append(n)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(verticalScroll),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Fast O(1) Line Number Gutter + drawBehind active/error highlights
                if (settings.showLineNumbers) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .background(syntaxColors.gutterBackground)
                            .drawBehind {
                                val topPadPx = 12.dp.toPx()
                                val layout = gutterLayout
                                val fallbackLinePx = lineHeightSp.toPx()
                                fun bandForLine(lineNum: Int): Pair<Float, Float> {
                                    val idx = lineNum - 1
                                    if (layout != null && idx in 0 until layout.lineCount) {
                                        val t = topPadPx + layout.getLineTop(idx)
                                        val b = topPadPx + layout.getLineBottom(idx)
                                        return t to (b - t)
                                    }
                                    return (topPadPx + idx * fallbackLinePx) to fallbackLinePx
                                }
                                if (currentLine in 1..lineCount) {
                                    val (y, h) = bandForLine(currentLine)
                                    drawRect(
                                        color = syntaxColors.activeLineBackground,
                                        topLeft = Offset(0f, y),
                                        size = Size(size.width, h),
                                    )
                                }
                                errorLineNumbers.forEach { errLine ->
                                    if (errLine in 1..lineCount) {
                                        val (y, h) = bandForLine(errLine)
                                        drawRect(
                                            color = syntaxColors.errorLineBackground,
                                            topLeft = Offset(0f, y),
                                            size = Size(size.width, h),
                                        )
                                        drawCircle(
                                            color = errorDotColor,
                                            radius = 3.dp.toPx(),
                                            center = Offset(7.dp.toPx(), y + h / 2f),
                                        )
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = lineNumbersText,
                            style = TextStyle(
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = (settings.fontSizeSp - 2).coerceAtLeast(9).sp,
                                lineHeight = lineHeightSp,
                                color = syntaxColors.gutterText,
                                textAlign = TextAlign.End,
                            ),
                            onTextLayout = { gutterLayout = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Editable Code Text Field
                val fieldModifier = if (settings.wordWrap) {
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .padding(horizontal = 10.dp, vertical = 12.dp)
                }

                BasicTextField(
                    value = textValue,
                    onValueChange = { incoming ->
                        val processed = if (
                            settings.autoIndent &&
                            incoming.text.length == textValue.text.length + 1 &&
                            incoming.selection.collapsed &&
                            incoming.selection.start > 0 &&
                            incoming.text[incoming.selection.start - 1] == '\n'
                        ) {
                            applyAutoIndentOnNewline(incoming)
                        } else {
                            incoming
                        }
                        applyEdit(processed)
                    },
                    textStyle = codeTextStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = visualTransformation,
                    modifier = fieldModifier.testTag("code_editor_field"),
                )
            }
        }

        // Compact Editor Status Strip
        if (settings.showStatusBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${language.name} • $lineCount lines",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (errorLineNumbers.isNotEmpty() && onRequestFixWithAi != null) {
                            Surface(
                                onClick = onRequestFixWithAi,
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.testTag("editor_fix_with_ai_chip"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.AutoFixHigh,
                                        contentDescription = "Fix file errors with Error Agent",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "Fix ${errorLineNumbers.size} error(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                    val cursorOffset = textValue.selection.start.coerceIn(0, textValue.text.length)
                    val lineStart = textValue.text.lastIndexOf('\n', (cursorOffset - 1).coerceAtLeast(0))
                    val colNum = if (lineStart < 0) cursorOffset + 1 else cursorOffset - lineStart
                    Text(
                        text = "Ln $currentLine, Col $colNum",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentLine in errorLineNumbers) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Symbol & Android Quick-Snippet Accessory Bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        if (undoStack.isNotEmpty()) {
                            redoStack.add(textValue)
                            val prev = undoStack.removeAt(undoStack.lastIndex)
                            textValue = prev
                            onContentChange(prev.text)
                        }
                    },
                    enabled = undoStack.isNotEmpty(),
                    modifier = Modifier.testTag("editor_undo_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(
                    onClick = {
                        if (redoStack.isNotEmpty()) {
                            undoStack.add(textValue)
                            val next = redoStack.removeAt(redoStack.lastIndex)
                            textValue = next
                            onContentChange(next.text)
                        }
                    },
                    enabled = redoStack.isNotEmpty(),
                    modifier = Modifier.testTag("editor_redo_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
                IconButton(
                    onClick = { showFindBar = !showFindBar },
                    modifier = Modifier.testTag("editor_find_button"),
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Find and replace")
                }
                TextButton(
                    onClick = { showGoToLineBar = !showGoToLineBar },
                    modifier = Modifier.testTag("editor_goto_line_button"),
                ) {
                    Text(":Ln", fontFamily = JetBrainsMonoFontFamily, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(
                    onClick = {
                        val formatted = simpleIndentFormat(textValue.text, language)
                        applyEdit(TextFieldValue(formatted, TextRange(0)))
                    },
                    modifier = Modifier.testTag("editor_format_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, contentDescription = "Auto-indent file")
                }

                Spacer(Modifier.width(4.dp))

                val isKotlin = language == FileLanguage.KOTLIN
                val symbolChips = buildList {
                    add("TAB" to { insertSnippet("    ") })
                    add("{ }" to { insertSnippet("{\n    \n}", cursorBackOffset = 2) })
                    add("( )" to { insertSnippet("()", cursorBackOffset = 1) })
                    if (isKotlin) {
                        add("fun" to { insertSnippet("private fun () {\n    \n}", cursorBackOffset = 10) })
                        add("val" to { insertSnippet("val ") })
                        add("var" to { insertSnippet("var ") })
                    } else {
                        add(";" to { insertSnippet(";") })
                    }
                    add("=" to { insertSnippet(" = ") })
                    add("\"" to { insertSnippet("\"\"", cursorBackOffset = 1) })
                    add("." to { insertSnippet(".") })
                    add("//" to { insertSnippet("// ") })
                    add("<>" to { insertSnippet("<>", cursorBackOffset = 1) })
                    add(
                        "Toast" to {
                            val semi = if (isKotlin) "" else ";"
                            insertSnippet(
                                "android.widget.Toast.makeText(this, \"\", android.widget.Toast.LENGTH_SHORT).show()$semi",
                                cursorBackOffset = if (isKotlin) 45 else 46,
                            )
                        },
                    )
                    add(
                        "Log" to {
                            val semi = if (isKotlin) "" else ";"
                            insertSnippet(
                                "android.util.Log.d(\"App\", \"\")$semi",
                                cursorBackOffset = if (isKotlin) 2 else 3,
                            )
                        },
                    )
                    add(
                        "Btn" to {
                            if (isKotlin) {
                                insertSnippet(
                                    "val btn = Button(this)\nbtn.text = \"Tap\"\nbtn.setOnClickListener {\n    \n}",
                                    cursorBackOffset = 2,
                                )
                            } else {
                                insertSnippet(
                                    "Button btn = new Button(this);\nbtn.setText(\"Tap\");\nbtn.setOnClickListener(v -> {\n    \n});",
                                    cursorBackOffset = 4,
                                )
                            }
                        },
                    )
                }
                symbolChips.forEach { (label, action) ->
                    Surface(
                        onClick = action,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lineStartOffset(text: String, targetLine: Int): Int {
    if (targetLine <= 1) return 0
    var current = 1
    for (i in text.indices) {
        if (text[i] == '\n') {
            current++
            if (current == targetLine) return i + 1
        }
    }
    return text.length
}

private fun applyAutoIndentOnNewline(incoming: TextFieldValue): TextFieldValue {
    val cursor = incoming.selection.start
    val beforeNewline = incoming.text.substring(0, cursor - 1)
    val prevLineStart = beforeNewline.lastIndexOf('\n').let { if (it == -1) 0 else it + 1 }
    val prevLine = beforeNewline.substring(prevLineStart)
    val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
    val extraIndent = if (prevLine.trimEnd().endsWith("{")) "    " else ""
    val indent = baseIndent + extraIndent
    if (indent.isEmpty()) return incoming
    val updated = incoming.text.substring(0, cursor) + indent + incoming.text.substring(cursor)
    return TextFieldValue(updated, TextRange(cursor + indent.length))
}

private fun simpleIndentFormat(code: String, language: FileLanguage): String {
    if (language != FileLanguage.JAVA && language != FileLanguage.KOTLIN) {
        return code.lines().joinToString("\n") { it.trimEnd() }
    }
    var depth = 0
    val out = mutableListOf<String>()
    for (raw in code.lines()) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            out.add("")
            continue
        }
        if (trimmed.startsWith("}")) {
            depth = (depth - 1).coerceAtLeast(0)
        }
        out.add("    ".repeat(depth) + trimmed)
        val openCount = trimmed.count { it == '{' }
        val closeCount = trimmed.count { it == '}' }
        val net = openCount - closeCount
        if (trimmed.startsWith("}")) {
            depth = (depth + net + 1).coerceAtLeast(0)
        } else {
            depth = (depth + net).coerceAtLeast(0)
        }
    }
    return out.joinToString("\n")
}
