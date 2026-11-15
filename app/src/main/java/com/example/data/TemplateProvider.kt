package com.example.data

import com.example.model.ProjectTemplate

object TemplateProvider {

    fun generateFiles(
        template: ProjectTemplate,
        appName: String,
        packageName: String,
    ): Map<String, String> {
        val pkgPath = packageName.replace('.', '/')
        val files = linkedMapOf<String, String>()

        files["AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName"
                android:versionCode="1"
                android:versionName="1.0">

                <uses-sdk
                    android:minSdkVersion="24"
                    android:targetSdkVersion="35" />

                <application
                    android:allowBackup="true"
                    android:label="@string/app_name"
                    android:theme="@android:style/Theme.Material.Light.DarkActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:label="@string/app_name">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"

        files["res/values/strings.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${escapeXml(appName)}</string>
                <string name="welcome_subtitle">Built offline on Android with DroidForge</string>
            </resources>
        """.trimIndent() + "\n"

        files["res/values/colors.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="primary">#0284C7</color>
                <color name="accent">#10B981</color>
                <color name="surface">#F8FAFC</color>
            </resources>
        """.trimIndent() + "\n"

        when (template) {
            ProjectTemplate.COUNTER_APP -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.graphics.drawable.GradientDrawable;
                    import android.os.Bundle;
                    import android.view.Gravity;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;
                    import android.widget.Toast;

                    public class MainActivity extends Activity {

                        private int count = 0;
                        private TextView countDisplay;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setGravity(Gravity.CENTER);
                            root.setPadding(48, 48, 48, 48);
                            root.setBackgroundColor(Color.parseColor("#0F172A"));

                            TextView title = new TextView(this);
                            title.setText(getString(R.string.app_name));
                            title.setTextSize(26);
                            title.setTypeface(Typeface.DEFAULT_BOLD);
                            title.setTextColor(Color.parseColor("#F8FAFC"));
                            title.setGravity(Gravity.CENTER);
                            root.addView(title);

                            TextView subtitle = new TextView(this);
                            subtitle.setText(getString(R.string.welcome_subtitle));
                            subtitle.setTextSize(14);
                            subtitle.setTextColor(Color.parseColor("#94A3B8"));
                            subtitle.setGravity(Gravity.CENTER);
                            subtitle.setPadding(0, 12, 0, 48);
                            root.addView(subtitle);

                            countDisplay = new TextView(this);
                            countDisplay.setText("0");
                            countDisplay.setTextSize(64);
                            countDisplay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            countDisplay.setTextColor(Color.parseColor("#38BDF8"));
                            countDisplay.setGravity(Gravity.CENTER);
                            countDisplay.setPadding(64, 48, 64, 48);

                            GradientDrawable cardBg = new GradientDrawable();
                            cardBg.setColor(Color.parseColor("#1E293B"));
                            cardBg.setCornerRadius(36f);
                            cardBg.setStroke(2, Color.parseColor("#334155"));
                            countDisplay.setBackground(cardBg);
                            root.addView(countDisplay);

                            LinearLayout row = new LinearLayout(this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            row.setGravity(Gravity.CENTER);
                            row.setPadding(0, 48, 0, 0);

                            Button minusBtn = createActionButton("-1", "#334155");
                            minusBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    count--;
                                    updateCounter();
                                }
                            });

                            Button resetBtn = createActionButton("Reset", "#475569");
                            resetBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    count = 0;
                                    updateCounter();
                                    Toast.makeText(MainActivity.this, "Counter reset", Toast.LENGTH_SHORT).show();
                                }
                            });

                            Button plusBtn = createActionButton("+1", "#0284C7");
                            plusBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    count++;
                                    updateCounter();
                                }
                            });

                            row.addView(minusBtn);
                            row.addView(resetBtn);
                            row.addView(plusBtn);
                            root.addView(row);

                            setContentView(root);
                        }

                        private Button createActionButton(String label, String hexColor) {
                            Button btn = new Button(this);
                            btn.setText(label);
                            btn.setTextColor(Color.WHITE);
                            btn.setTextSize(16);
                            GradientDrawable bg = new GradientDrawable();
                            bg.setColor(Color.parseColor(hexColor));
                            bg.setCornerRadius(24f);
                            btn.setBackground(bg);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            lp.setMargins(14, 0, 14, 0);
                            btn.setLayoutParams(lp);
                            btn.setPadding(42, 24, 42, 24);
                            return btn;
                        }

                        private void updateCounter() {
                            countDisplay.setText(String.valueOf(count));
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.CANVAS_DRAW -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;

                    public class MainActivity extends Activity {

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0B0F17"));

                            final DrawingPadView pad = new DrawingPadView(this);
                            LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                            );
                            root.addView(pad, padParams);

                            LinearLayout toolbar = new LinearLayout(this);
                            toolbar.setOrientation(LinearLayout.HORIZONTAL);
                            toolbar.setPadding(24, 20, 24, 20);
                            toolbar.setBackgroundColor(Color.parseColor("#182030"));

                            int[] colors = {
                                Color.parseColor("#38BDF8"),
                                Color.parseColor("#34D399"),
                                Color.parseColor("#FBBF24"),
                                Color.parseColor("#F87171")
                            };
                            String[] names = {"Cyan", "Mint", "Gold", "Coral"};

                            for (int i = 0; i < colors.length; i++) {
                                final int c = colors[i];
                                Button swatch = new Button(this);
                                swatch.setText(names[i]);
                                swatch.setTextColor(Color.BLACK);
                                swatch.setBackgroundColor(c);
                                swatch.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        pad.setBrushColor(c);
                                    }
                                });
                                toolbar.addView(swatch);
                            }

                            Button clearBtn = new Button(this);
                            clearBtn.setText("Clear");
                            clearBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    pad.clearCanvas();
                                }
                            });
                            toolbar.addView(clearBtn);

                            root.addView(toolbar);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/DrawingPadView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.graphics.Path;
                    import android.view.MotionEvent;
                    import android.view.View;
                    import java.util.ArrayList;
                    import java.util.List;

                    public class DrawingPadView extends View {

                        private static class Stroke {
                            final Path path;
                            final int color;
                            Stroke(Path path, int color) {
                                this.path = path;
                                this.color = color;
                            }
                        }

                        private final List<Stroke> strokes = new ArrayList<>();
                        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private Path activePath;
                        private int currentColor = Color.parseColor("#38BDF8");

                        public DrawingPadView(Context context) {
                            super(context);
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setStrokeWidth(12f);
                            paint.setStrokeCap(Paint.Cap.ROUND);
                            paint.setStrokeJoin(Paint.Join.ROUND);
                        }

                        public void setBrushColor(int color) {
                            currentColor = color;
                        }

                        public void clearCanvas() {
                            strokes.clear();
                            invalidate();
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            for (Stroke s : strokes) {
                                paint.setColor(s.color);
                                canvas.drawPath(s.path, paint);
                            }
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            float x = event.getX();
                            float y = event.getY();
                            switch (event.getAction()) {
                                case MotionEvent.ACTION_DOWN:
                                    activePath = new Path();
                                    activePath.moveTo(x, y);
                                    strokes.add(new Stroke(activePath, currentColor));
                                    invalidate();
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    if (activePath != null) {
                                        activePath.lineTo(x, y);
                                        invalidate();
                                    }
                                    return true;
                            }
                            return super.onTouchEvent(event);
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.CALCULATOR -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.os.Bundle;
                    import android.view.Gravity;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.GridLayout;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {

                        private TextView display;
                        private double firstOperand = 0;
                        private String pendingOp = "";
                        private boolean clearOnNextDigit = true;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0B0F17"));
                            root.setPadding(24, 48, 24, 24);

                            display = new TextView(this);
                            display.setText("0");
                            display.setTextSize(48);
                            display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            display.setTextColor(Color.WHITE);
                            display.setGravity(Gravity.END | Gravity.BOTTOM);
                            display.setPadding(24, 48, 24, 36);
                            root.addView(display);

                            GridLayout grid = new GridLayout(this);
                            grid.setColumnCount(4);
                            grid.setRowCount(4);

                            String[] keys = {
                                "7", "8", "9", "/",
                                "4", "5", "6", "*",
                                "1", "2", "3", "-",
                                "C", "0", "=", "+"
                            };

                            for (final String key : keys) {
                                Button btn = new Button(this);
                                btn.setText(key);
                                btn.setTextSize(22);
                                btn.setTextColor(Color.WHITE);
                                btn.setBackgroundColor(
                                    key.matches("[0-9]") ? Color.parseColor("#1E293B") : Color.parseColor("#0284C7")
                                );
                                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                                params.width = 0;
                                params.height = 180;
                                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                                params.setMargins(8, 8, 8, 8);
                                btn.setLayoutParams(params);
                                btn.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        onKeyPress(key);
                                    }
                                });
                                grid.addView(btn);
                            }

                            root.addView(grid);
                            setContentView(root);
                        }

                        private void onKeyPress(String key) {
                            if (key.equals("C")) {
                                firstOperand = 0;
                                pendingOp = "";
                                clearOnNextDigit = true;
                                display.setText("0");
                            } else if (key.matches("[0-9]")) {
                                String current = clearOnNextDigit ? "" : display.getText().toString();
                                display.setText(current + key);
                                clearOnNextDigit = false;
                            } else if (key.equals("=")) {
                                double second = Double.parseDouble(display.getText().toString());
                                double result = second;
                                if (pendingOp.equals("+")) result = firstOperand + second;
                                else if (pendingOp.equals("-")) result = firstOperand - second;
                                else if (pendingOp.equals("*")) result = firstOperand * second;
                                else if (pendingOp.equals("/")) result = second == 0 ? 0 : firstOperand / second;
                                display.setText(result == (long) result ? String.valueOf((long) result) : String.valueOf(result));
                                pendingOp = "";
                                clearOnNextDigit = true;
                            } else {
                                firstOperand = Double.parseDouble(display.getText().toString());
                                pendingOp = key;
                                clearOnNextDigit = true;
                            }
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.NOTES_APP -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.graphics.drawable.GradientDrawable;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.EditText;
                    import android.widget.LinearLayout;
                    import android.widget.ScrollView;
                    import android.widget.TextView;
                    import android.widget.Toast;

                    public class MainActivity extends Activity {

                        private EditText noteInput;
                        private LinearLayout notesContainer;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0F172A"));
                            root.setPadding(36, 48, 36, 36);

                            TextView header = new TextView(this);
                            header.setText(getString(R.string.app_name));
                            header.setTextSize(24);
                            header.setTypeface(Typeface.DEFAULT_BOLD);
                            header.setTextColor(Color.WHITE);
                            header.setPadding(0, 0, 0, 24);
                            root.addView(header);

                            noteInput = new EditText(this);
                            noteInput.setHint("Write a quick note...");
                            noteInput.setTextColor(Color.WHITE);
                            noteInput.setHintTextColor(Color.parseColor("#94A3B8"));
                            noteInput.setBackgroundColor(Color.parseColor("#1E293B"));
                            noteInput.setPadding(28, 24, 28, 24);
                            root.addView(noteInput);

                            Button addBtn = new Button(this);
                            addBtn.setText("Save Note");
                            addBtn.setTextColor(Color.WHITE);
                            GradientDrawable btnBg = new GradientDrawable();
                            btnBg.setColor(Color.parseColor("#0284C7"));
                            btnBg.setCornerRadius(20f);
                            addBtn.setBackground(btnBg);
                            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            btnLp.setMargins(0, 20, 0, 24);
                            addBtn.setLayoutParams(btnLp);
                            root.addView(addBtn);

                            ScrollView scroll = new ScrollView(this);
                            notesContainer = new LinearLayout(this);
                            notesContainer.setOrientation(LinearLayout.VERTICAL);
                            scroll.addView(notesContainer);
                            root.addView(scroll);

                            addBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    String content = noteInput.getText().toString().trim();
                                    if (content.isEmpty()) return;
                                    addNoteCard(content);
                                    noteInput.setText("");
                                    Toast.makeText(MainActivity.this, "Note saved", Toast.LENGTH_SHORT).show();
                                }
                            });

                            setContentView(root);
                        }

                        private void addNoteCard(String text) {
                            TextView card = new TextView(this);
                            card.setText(text);
                            card.setTextColor(Color.parseColor("#E2E8F0"));
                            card.setTextSize(15);
                            card.setPadding(32, 24, 32, 24);
                            GradientDrawable bg = new GradientDrawable();
                            bg.setColor(Color.parseColor("#1E293B"));
                            bg.setCornerRadius(20f);
                            card.setBackground(bg);
                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            );
                            lp.setMargins(0, 0, 0, 16);
                            card.setLayoutParams(lp);
                            notesContainer.addView(card, 0);
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.STOPWATCH_APP -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.os.Bundle;
                    import android.os.Handler;
                    import android.os.Looper;
                    import android.view.Gravity;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {

                        private final Handler handler = new Handler(Looper.getMainLooper());
                        private TextView readout;
                        private TextView lapsView;
                        private boolean running = false;
                        private long elapsedMs = 0L;
                        private long lastTickMs = 0L;
                        private int lapCount = 0;

                        private final Runnable ticker = new Runnable() {
                            @Override
                            public void run() {
                                if (!running) return;
                                long now = System.currentTimeMillis();
                                elapsedMs += (now - lastTickMs);
                                lastTickMs = now;
                                updateReadout();
                                handler.postDelayed(this, 80);
                            }
                        };

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setGravity(Gravity.CENTER_HORIZONTAL);
                            root.setBackgroundColor(Color.parseColor("#0B0F17"));
                            root.setPadding(40, 72, 40, 40);

                            readout = new TextView(this);
                            readout.setText("00:00.0");
                            readout.setTextSize(54);
                            readout.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            readout.setTextColor(Color.parseColor("#34D399"));
                            readout.setPadding(0, 32, 0, 40);
                            root.addView(readout);

                            LinearLayout controls = new LinearLayout(this);
                            controls.setOrientation(LinearLayout.HORIZONTAL);
                            controls.setGravity(Gravity.CENTER);

                            Button toggleBtn = new Button(this);
                            toggleBtn.setText("Start / Pause");
                            toggleBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    running = !running;
                                    if (running) {
                                        lastTickMs = System.currentTimeMillis();
                                        handler.post(ticker);
                                    } else {
                                        handler.removeCallbacks(ticker);
                                    }
                                }
                            });

                            Button lapBtn = new Button(this);
                            lapBtn.setText("Lap");
                            lapBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    lapCount++;
                                    String line = "Lap " + lapCount + ": " + readout.getText() + "\n" + lapsView.getText();
                                    lapsView.setText(line);
                                }
                            });

                            Button resetBtn = new Button(this);
                            resetBtn.setText("Reset");
                            resetBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    running = false;
                                    handler.removeCallbacks(ticker);
                                    elapsedMs = 0L;
                                    lapCount = 0;
                                    lapsView.setText("");
                                    updateReadout();
                                }
                            });

                            controls.addView(toggleBtn);
                            controls.addView(lapBtn);
                            controls.addView(resetBtn);
                            root.addView(controls);

                            lapsView = new TextView(this);
                            lapsView.setTextColor(Color.parseColor("#94A3B8"));
                            lapsView.setTypeface(Typeface.MONOSPACE);
                            lapsView.setPadding(0, 32, 0, 0);
                            root.addView(lapsView);

                            setContentView(root);
                        }

                        private void updateReadout() {
                            long tenths = (elapsedMs / 100) % 10;
                            long totalSecs = elapsedMs / 1000;
                            long mins = totalSecs / 60;
                            long secs = totalSecs % 60;
                            readout.setText(String.format("%02d:%02d.%d", mins, secs, tenths));
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.BLANK_ACTIVITY -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.os.Bundle;
                    import android.view.Gravity;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            TextView tv = new TextView(this);
                            tv.setText("Hello from " + getString(R.string.app_name) + "!");
                            tv.setTextSize(22);
                            tv.setGravity(Gravity.CENTER);
                            setContentView(tv);
                        }
                    }
                """.trimIndent() + "\n"
            }
        }
        return files
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
