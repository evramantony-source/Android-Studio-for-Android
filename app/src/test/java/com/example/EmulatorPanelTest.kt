package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.TemplateProvider
import com.example.emulator.EmulatorEngine
import com.example.emulator.EmulatorPanel
import com.example.model.FileLanguage
import com.example.model.ProjectFile
import com.example.model.ProjectLanguage
import com.example.model.ProjectTemplate
import com.example.ui.theme.DroidForgeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h892dp")
class EmulatorPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun toProjectFiles(rawMap: Map<String, String>): List<ProjectFile> =
        rawMap.map { (path, content) ->
            val lang = when {
                path.endsWith(".kt") -> FileLanguage.KOTLIN
                path.endsWith(".java") -> FileLanguage.JAVA
                path.endsWith(".xml") -> FileLanguage.XML
                path.endsWith(".json") -> FileLanguage.JSON
                else -> FileLanguage.TEXT
            }
            ProjectFile(relativePath = path, content = content, language = lang)
        }

    @Test
    fun allTemplatesBootAndSnapshotCleanlyInJavaAndKotlin() {
        for (lang in ProjectLanguage.entries) {
            for (template in ProjectTemplate.entries) {
                val files = toProjectFiles(
                    TemplateProvider.generateFiles(
                        template = template,
                        appName = template.defaultAppName,
                        packageName = template.defaultPackage,
                        language = lang,
                    ),
                )
                val session = EmulatorEngine.createLiveSession(
                    appName = template.defaultAppName,
                    packageName = template.defaultPackage,
                    files = files,
                )
                assertNotNull("Expected LiveVmSession for $template ($lang)", session)
                assertTrue("Expected start() to succeed for $template ($lang)", session!!.start())
                assertTrue("Expected updateViewportSize to return true on first resize", session.updateViewportSize(640, 1120))
                assertFalse("Expected second identical updateViewportSize to return false", session.updateViewportSize(640, 1120))
                val snapshot = session.buildSnapshot()
                assertNotNull("Expected non-null snapshot for $template ($lang)", snapshot)
                val program = EmulatorEngine.parseProject(template.defaultAppName, template.defaultPackage, files)
                assertNotNull(program.root)
            }
        }
    }

    @Test
    fun counterAppRendersAndHandlesToolbarAndInteractionsWithoutCrashing() {
        val files = toProjectFiles(
            TemplateProvider.generateFiles(
                template = ProjectTemplate.COUNTER_APP,
                appName = "Counter App",
                packageName = "com.example.counter",
                language = ProjectLanguage.JAVA,
            ),
        )

        composeTestRule.setContent {
            DroidForgeTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    EmulatorPanel(
                        appName = "Counter App",
                        packageName = "com.example.counter",
                        files = files,
                        builtApkPath = null,
                        onInstallApk = {},
                        onClose = {},
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("+1"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("+1").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()

        composeTestRule.onNodeWithTag("emulator_rotate_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("emulator_logcat_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Emulator Logcat"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Emulator Logcat").assertIsDisplayed()

        composeTestRule.onNodeWithTag("emulator_restart_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("0"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun canvasTemplatesAndScrollableWeightedContainersRenderWithoutLayoutCrash() {
        val canvasFiles = toProjectFiles(
            TemplateProvider.generateFiles(
                template = ProjectTemplate.CANVAS_DRAW,
                appName = "SketchPad",
                packageName = "com.example.sketchpad",
                language = ProjectLanguage.JAVA,
            ),
        )
        // Also test a ScrollView containing a child with layout_weight > 0 AND a custom Canvas View
        val scrollWithWeightedCanvas = listOf(
            ProjectFile(
                relativePath = "AndroidManifest.xml",
                content = "<manifest package=\"com.example.scroll\"><application android:label=\"ScrollCanvas\"/></manifest>",
                language = FileLanguage.XML,
            ),
            ProjectFile(
                relativePath = "res/values/strings.xml",
                content = "<resources><string name=\"app_name\">ScrollCanvas</string></resources>",
                language = FileLanguage.XML,
            ),
            ProjectFile(
                relativePath = "src/com/example/scroll/MainActivity.java",
                content = """
                    package com.example.scroll;
                    import android.app.Activity;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.LinearLayout;
                    import android.widget.ScrollView;
                    import android.widget.TextView;
                    public class MainActivity extends Activity {
                        public static class OddCanvas extends View {
                            public OddCanvas(android.content.Context c) { super(c); }
                            @Override
                            protected void onDraw(Canvas canvas) {
                                Paint p = new Paint();
                                p.setColor(Color.CYAN);
                                canvas.drawRect(Float.NaN, -10f, 100f, 50f, p);
                                canvas.drawCircle(20f, 20f, -5f, p);
                                canvas.save();
                                canvas.save();
                                // intentionally missing restore + NaN scale
                                canvas.scale(Float.NaN, 1f, 0f, 0f);
                            }
                        }
                        @Override
                        protected void onCreate(Bundle b) {
                            super.onCreate(b);
                            ScrollView sv = new ScrollView(this);
                            LinearLayout col = new LinearLayout(this);
                            col.setOrientation(LinearLayout.VERTICAL);
                            TextView tv = new TextView(this);
                            tv.setText("Header");
                            col.addView(tv, new LinearLayout.LayoutParams(-1, 0, 1f));
                            col.addView(new OddCanvas(this), new LinearLayout.LayoutParams(-1, 0, 2f));
                            sv.addView(col);
                            setContentView(sv);
                        }
                    }
                """.trimIndent(),
                language = FileLanguage.JAVA,
            ),
        )

        composeTestRule.setContent {
            DroidForgeTheme(darkTheme = true) {
                Box(modifier = Modifier.size(360.dp, 640.dp)) {
                    EmulatorPanel(
                        appName = "ScrollCanvas",
                        packageName = "com.example.scroll",
                        files = scrollWithWeightedCanvas,
                        builtApkPath = null,
                        onInstallApk = {},
                        onClose = {},
                    )
                }
            }
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Header"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        // Exercise fallback DrawingCanvasWidget path inside tiny viewport as well
        val fallbackOnlyCanvas = canvasFiles.filterNot { it.fileName == "DrawingPadView.java" }
            .map {
                if (it.fileName == "MainActivity.java") {
                    it.copy(
                        content = "package com.example.sketchpad;\n// DrawingPadView marker with unparsable syntax {{{\n",
                    )
                } else it
            }

        val prog = EmulatorEngine.parseProject("SketchPad", "com.example.sketchpad", fallbackOnlyCanvas)
        assertNotNull(prog.root)
    }

    @Test
    fun tinyViewportDoesNotCrashOrThrowMeasureException() {
        val files = toProjectFiles(
            TemplateProvider.generateFiles(
                template = ProjectTemplate.BLANK_ACTIVITY,
                appName = "TinyTest",
                packageName = "com.example.tiny",
                language = ProjectLanguage.JAVA,
            ),
        )

        composeTestRule.setContent {
            DroidForgeTheme(darkTheme = true) {
                Box(modifier = Modifier.size(90.dp, 120.dp)) {
                    EmulatorPanel(
                        appName = "TinyTest",
                        packageName = "com.example.tiny",
                        files = files,
                        builtApkPath = null,
                        onInstallApk = {},
                        onClose = {},
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun liveVmHandlesCyclesDeepRecursionBrokenRegexMalformedXmlAndTimersSafely() {
        val hostileFiles = listOf(
            ProjectFile(
                relativePath = "res/layout/activity_main.xml",
                content = "<LinearLayout><BrokenXml",
                language = FileLanguage.XML,
            ),
            ProjectFile(
                relativePath = "src/com/example/hostile/MainActivity.java",
                content = """
                    package com.example.hostile;
                    import android.app.Activity;
                    import android.os.Bundle;
                    import android.os.Handler;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;

                    class CycleA extends CycleB {}
                    class CycleB extends CycleA {}

                    public class MainActivity extends Activity {
                        private int ticks = 0;

                        private int recurse(int n) {
                            return recurse(n + 1) + 1;
                        }

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            setContentView(R.layout.activity_main);
                            CycleA cyc = new CycleA();
                            boolean inst = cyc instanceof Activity;
                            String[] parts = "a*b*c".split("*");
                            recurse(0);

                            LinearLayout root = new LinearLayout(this);
                            root.addView(root); // cyclic self-add must be ignored
                            TextView tv = new TextView(this);
                            tv.setText("Survived " + parts.length + " " + inst);
                            root.addView(tv);
                            setContentView(root);

                            final Handler h = new Handler();
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    ticks++;
                                    String[] bad = "x".split("[unclosed");
                                    h.postDelayed(this, 16);
                                }
                            });
                        }
                    }
                """.trimIndent(),
                language = FileLanguage.JAVA,
            ),
        )

        val session = EmulatorEngine.createLiveSession(
            appName = "Hostile",
            packageName = "com.example.hostile",
            files = hostileFiles,
        )
        assertNotNull(session)
        assertTrue(session!!.start())

        val snapshot = session.buildSnapshot()
        assertNotNull(snapshot)

        // Pump 1500 timer ticks to verify no uncaught timer crash & stepBudget resets per tick
        var now = System.currentTimeMillis() + 50L
        var firedCount = 0
        repeat(1500) {
            now += 20L
            if (session.tickTimers(now)) firedCount++
        }
        assertEquals(1500, firedCount)
    }

    @Test
    fun emulatorResetsAppStateFreshOnReEntryLikeColdBoot() {
        val files = toProjectFiles(
            TemplateProvider.generateFiles(
                template = ProjectTemplate.COUNTER_APP,
                appName = "Counter App",
                packageName = "com.example.counter",
                language = ProjectLanguage.JAVA,
            ),
        )
        val showEmulator = androidx.compose.runtime.mutableStateOf(true)

        composeTestRule.setContent {
            DroidForgeTheme(darkTheme = true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (showEmulator.value) {
                        EmulatorPanel(
                            appName = "Counter App",
                            packageName = "com.example.counter",
                            files = files,
                            builtApkPath = null,
                            onInstallApk = {},
                            onClose = { showEmulator.value = false },
                        )
                    }
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("+1")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("+1").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()

        // Leave emulator and re-enter: counter must reset from 0 cleanly.
        showEmulator.value = false
        composeTestRule.waitForIdle()
        showEmulator.value = true
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(androidx.compose.ui.test.hasText("0")).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }
}
