package com.example.data

import com.example.model.ProjectLanguage
import com.example.model.ProjectTemplate

object TemplateProvider {

    fun generateFiles(
        template: ProjectTemplate,
        appName: String,
        packageName: String,
        language: ProjectLanguage = ProjectLanguage.JAVA,
        minSdkVersion: Int = 24,
    ): Map<String, String> {
        val pkgPath = packageName.replace('.', '/')
        val safeMinSdk = minSdkVersion.coerceIn(21, 35)
        val files = linkedMapOf<String, String>()

        files["AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName"
                android:versionCode="1"
                android:versionName="1.0">

                <uses-sdk
                    android:minSdkVersion="$safeMinSdk"
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

        if (language == ProjectLanguage.KOTLIN) {
            generateKotlinSources(template, packageName, pkgPath, files)
        } else {
            generateJavaSources(template, packageName, pkgPath, files)
        }
        return files
    }

    private fun generateJavaSources(
        template: ProjectTemplate,
        packageName: String,
        pkgPath: String,
        files: MutableMap<String, String>,
    ) {
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

            ProjectTemplate.ARCADE_SHOOTER -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.os.Bundle;

                    public class MainActivity extends Activity {
                        private ShooterView gameView;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            gameView = new ShooterView(this);
                            setContentView(gameView);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/ShooterView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.graphics.Path;
                    import android.os.Handler;
                    import android.os.Looper;
                    import android.view.MotionEvent;
                    import android.view.View;
                    import java.util.ArrayList;
                    import java.util.Random;

                    public class ShooterView extends View {

                        private static class Bullet {
                            float x;
                            float y;
                            Bullet(float x, float y) { this.x = x; this.y = y; }
                        }

                        private static class Enemy {
                            float x;
                            float y;
                            float vy;
                            int hp;
                            int maxHp;
                            Enemy(float x, float y, float vy, int hp) {
                                this.x = x; this.y = y; this.vy = vy; this.hp = hp; this.maxHp = hp;
                            }
                        }

                        private static class Particle {
                            float x;
                            float y;
                            float vx;
                            float vy;
                            int life;
                            int color;
                            Particle(float x, float y, float vx, float vy, int color) {
                                this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.life = 18; this.color = color;
                            }
                        }

                        private static class Star {
                            float x;
                            float y;
                            float speed;
                            Star(float x, float y, float speed) { this.x = x; this.y = y; this.speed = speed; }
                        }

                        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private final Handler handler = new Handler(Looper.getMainLooper());
                        private final Random rng = new Random();
                        private final ArrayList<Bullet> bullets = new ArrayList<Bullet>();
                        private final ArrayList<Enemy> enemies = new ArrayList<Enemy>();
                        private final ArrayList<Particle> particles = new ArrayList<Particle>();
                        private final ArrayList<Star> stars = new ArrayList<Star>();

                        private float playerX = 450f;
                        private float playerY = 1200f;
                        private int score = 0;
                        private int wave = 1;
                        private int lives = 3;
                        private int tickCount = 0;
                        private boolean gameOver = false;

                        private final Runnable loop = new Runnable() {
                            @Override
                            public void run() {
                                updateFrame();
                                invalidate();
                                handler.postDelayed(this, 16);
                            }
                        };

                        public ShooterView(Context context) {
                            super(context);
                            for (int i = 0; i < 42; i++) {
                                stars.add(new Star(rng.nextFloat() * 1000f, rng.nextFloat() * 1600f, 1.5f + rng.nextFloat() * 4f));
                            }
                            handler.postDelayed(loop, 16);
                        }

                        @Override
                        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                            super.onSizeChanged(w, h, oldw, oldh);
                            if (w > 0 && h > 0) {
                                playerX = w * 0.5f;
                                playerY = h - 130f;
                            }
                        }

                        private void resetGame() {
                            bullets.clear();
                            enemies.clear();
                            particles.clear();
                            score = 0;
                            wave = 1;
                            lives = 3;
                            tickCount = 0;
                            gameOver = false;
                            playerX = getWidth() * 0.5f;
                        }

                        private void updateFrame() {
                            int w = getWidth();
                            int h = getHeight();
                            if (w <= 0 || h <= 0) return;

                            for (int i = 0; i < stars.size(); i++) {
                                Star s = stars.get(i);
                                s.y += s.speed;
                                if (s.y > h) {
                                    s.y = 0f;
                                    s.x = rng.nextFloat() * w;
                                }
                            }

                            if (gameOver) return;
                            tickCount++;
                            playerY = h - 130f;

                            if (tickCount % 10 == 0) {
                                bullets.add(new Bullet(playerX - 18f, playerY - 46f));
                                bullets.add(new Bullet(playerX + 18f, playerY - 46f));
                            }

                            int spawnInterval = Math.max(18, 46 - wave * 3);
                            if (tickCount % spawnInterval == 0) {
                                float ex = 50f + rng.nextFloat() * Math.max(100f, w - 100f);
                                int hp = 1 + (wave / 2);
                                float vy = 3.2f + wave * 0.55f;
                                enemies.add(new Enemy(ex, -40f, vy, hp));
                            }

                            for (int i = bullets.size() - 1; i >= 0; i--) {
                                Bullet b = bullets.get(i);
                                b.y -= 20f;
                                if (b.y < -30f) bullets.remove(i);
                            }

                            for (int i = enemies.size() - 1; i >= 0; i--) {
                                Enemy e = enemies.get(i);
                                e.y += e.vy;

                                if (Math.abs(e.x - playerX) < 48f && Math.abs(e.y - playerY) < 48f) {
                                    enemies.remove(i);
                                    lives--;
                                    spawnExplosion(e.x, e.y, Color.parseColor("#F87171"));
                                    if (lives <= 0) gameOver = true;
                                    continue;
                                }

                                if (e.y > h + 40f) {
                                    enemies.remove(i);
                                    continue;
                                }

                                boolean hit = false;
                                for (int j = bullets.size() - 1; j >= 0; j--) {
                                    Bullet b = bullets.get(j);
                                    if (Math.abs(b.x - e.x) < 34f && Math.abs(b.y - e.y) < 34f) {
                                        bullets.remove(j);
                                        e.hp--;
                                        spawnExplosion(b.x, b.y, Color.parseColor("#38BDF8"));
                                        if (e.hp <= 0) {
                                            score += 100;
                                            wave = 1 + score / 800;
                                            spawnExplosion(e.x, e.y, Color.parseColor("#FBBF24"));
                                            enemies.remove(i);
                                            hit = true;
                                            break;
                                        }
                                    }
                                }
                                if (hit) continue;
                            }

                            for (int i = particles.size() - 1; i >= 0; i--) {
                                Particle p = particles.get(i);
                                p.x += p.vx;
                                p.y += p.vy;
                                p.life--;
                                if (p.life <= 0) particles.remove(i);
                            }
                        }

                        private void spawnExplosion(float x, float y, int color) {
                            for (int i = 0; i < 6; i++) {
                                float vx = (rng.nextFloat() - 0.5f) * 10f;
                                float vy = (rng.nextFloat() - 0.5f) * 10f;
                                particles.add(new Particle(x, y, vx, vy, color));
                            }
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            int w = getWidth();
                            int h = getHeight();
                            canvas.drawColor(Color.parseColor("#060A14"));

                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(Color.parseColor("#64748B"));
                            for (int i = 0; i < stars.size(); i++) {
                                Star s = stars.get(i);
                                canvas.drawCircle(s.x % Math.max(1, w), s.y % Math.max(1, h), 2.5f, paint);
                            }

                            // Laser bolts
                            paint.setColor(Color.parseColor("#38BDF8"));
                            for (int i = 0; i < bullets.size(); i++) {
                                Bullet b = bullets.get(i);
                                canvas.drawRoundRect(b.x - 4f, b.y - 14f, b.x + 4f, b.y + 14f, 4f, 4f, paint);
                            }

                            // Enemy ships
                            for (int i = 0; i < enemies.size(); i++) {
                                Enemy e = enemies.get(i);
                                paint.setColor(Color.parseColor("#F43F5E"));
                                canvas.drawCircle(e.x, e.y, 26f, paint);
                                paint.setColor(Color.parseColor("#FDA4AF"));
                                canvas.drawCircle(e.x, e.y - 6f, 10f, paint);
                                if (e.maxHp > 1) {
                                    paint.setColor(Color.parseColor("#34D399"));
                                    float frac = (float) e.hp / (float) e.maxHp;
                                    canvas.drawRect(e.x - 24f, e.y - 36f, e.x - 24f + 48f * frac, e.y - 30f, paint);
                                }
                            }

                            // Particles
                            for (int i = 0; i < particles.size(); i++) {
                                Particle p = particles.get(i);
                                paint.setColor(p.color);
                                canvas.drawCircle(p.x, p.y, 4f, paint);
                            }

                            // Player Fighter Jet
                            Path ship = new Path();
                            ship.moveTo(playerX, playerY - 40f);
                            ship.lineTo(playerX + 34f, playerY + 26f);
                            ship.lineTo(playerX, playerY + 12f);
                            ship.lineTo(playerX - 34f, playerY + 26f);
                            ship.close();
                            paint.setColor(Color.parseColor("#22D3EE"));
                            canvas.drawPath(ship, paint);
                            paint.setColor(Color.parseColor("#FBBF24"));
                            canvas.drawCircle(playerX, playerY + 22f, 8f, paint);

                            // HUD
                            paint.setColor(Color.WHITE);
                            paint.setTextSize(34f);
                            paint.setTextAlign(Paint.Align.LEFT);
                            canvas.drawText("SCORE " + score + "   WAVE " + wave, 28f, 52f, paint);
                            paint.setColor(Color.parseColor("#F87171"));
                            paint.setTextAlign(Paint.Align.RIGHT);
                            canvas.drawText("HP x" + lives, w - 28f, 52f, paint);

                            if (gameOver) {
                                paint.setColor(Color.parseColor("#CC000000"));
                                canvas.drawRect(0f, h * 0.35f, w, h * 0.65f, paint);
                                paint.setColor(Color.parseColor("#F87171"));
                                paint.setTextAlign(Paint.Align.CENTER);
                                paint.setTextSize(54f);
                                canvas.drawText("MISSION FAILED", w * 0.5f, h * 0.47f, paint);
                                paint.setColor(Color.WHITE);
                                paint.setTextSize(32f);
                                canvas.drawText("Tap anywhere to launch again", w * 0.5f, h * 0.56f, paint);
                            }
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_DOWN && gameOver) {
                                resetGame();
                                invalidate();
                                return true;
                            }
                            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                                playerX = Math.max(40f, Math.min(getWidth() - 40f, event.getX()));
                                invalidate();
                                return true;
                            }
                            return super.onTouchEvent(event);
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.VOXEL_LAUNCHER_STUDIO -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;
                    import android.widget.Toast;

                    public class MainActivity extends Activity {

                        private VoxelWorldView worldView;
                        private TextView statusText;
                        private int javaTarget = 21;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);

                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0B1120"));

                            LinearLayout header = new LinearLayout(this);
                            header.setOrientation(LinearLayout.HORIZONTAL);
                            header.setBackgroundColor(Color.parseColor("#111827"));
                            header.setPadding(24, 18, 24, 18);

                            statusText = new TextView(this);
                            statusText.setText("Voxel Sandbox • JVM JDK " + javaTarget + " • 512MB Heap");
                            statusText.setTextColor(Color.parseColor("#38BDF8"));
                            statusText.setTextSize(13);
                            LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                            );
                            header.addView(statusText, statusLp);

                            Button jvmBtn = new Button(this);
                            jvmBtn.setText("Switch JDK");
                            jvmBtn.setTextColor(Color.WHITE);
                            jvmBtn.setBackgroundColor(Color.parseColor("#2563EB"));
                            jvmBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    if (javaTarget == 8) javaTarget = 17;
                                    else if (javaTarget == 17) javaTarget = 21;
                                    else if (javaTarget == 21) javaTarget = 25;
                                    else javaTarget = 8;
                                    statusText.setText("Voxel Sandbox • JVM JDK " + javaTarget + " • Ready");
                                    Toast.makeText(MainActivity.this, "Runtime target switched to Java " + javaTarget, Toast.LENGTH_SHORT).show();
                                }
                            });
                            header.addView(jvmBtn);
                            root.addView(header);

                            worldView = new VoxelWorldView(this);
                            LinearLayout.LayoutParams worldLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                            );
                            root.addView(worldView, worldLp);

                            LinearLayout hotbar = new LinearLayout(this);
                            hotbar.setOrientation(LinearLayout.HORIZONTAL);
                            hotbar.setBackgroundColor(Color.parseColor("#1E293B"));
                            hotbar.setPadding(16, 12, 16, 12);

                            String[] blockNames = {"Mine", "Grass", "Stone", "Timber", "Glow"};
                            final int[] blockIds = {0, 1, 2, 3, 4};
                            String[] hexColors = {"#475569", "#22C55E", "#64748B", "#B45309", "#FACC15"};

                            for (int i = 0; i < blockNames.length; i++) {
                                final int id = blockIds[i];
                                final String label = blockNames[i];
                                Button slot = new Button(this);
                                slot.setText(label);
                                slot.setTextColor(id == 4 ? Color.BLACK : Color.WHITE);
                                slot.setBackgroundColor(Color.parseColor(hexColors[i]));
                                LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(
                                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                                );
                                slotLp.setMargins(6, 0, 6, 0);
                                slot.setLayoutParams(slotLp);
                                slot.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        worldView.setSelectedBlock(id);
                                        Toast.makeText(MainActivity.this, "Tool: " + label, Toast.LENGTH_SHORT).show();
                                    }
                                });
                                hotbar.addView(slot);
                            }

                            root.addView(hotbar);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/VoxelWorldView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.view.MotionEvent;
                    import android.view.View;

                    public class VoxelWorldView extends View {

                        private static final int COLS = 10;
                        private static final int ROWS = 12;
                        private final int[] grid = new int[COLS * ROWS];
                        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private int selectedBlock = 1; // 0=Air(Mine), 1=Grass, 2=Stone, 3=Timber, 4=Glow

                        public VoxelWorldView(Context context) {
                            super(context);
                            generateTerrain();
                        }

                        public void setSelectedBlock(int blockId) {
                            this.selectedBlock = blockId;
                            invalidate();
                        }

                        private void generateTerrain() {
                            for (int r = 0; r < ROWS; r++) {
                                for (int c = 0; c < COLS; c++) {
                                    int idx = r * COLS + c;
                                    if (r == 6) grid[idx] = 1;
                                    else if (r > 6 && r < 10) grid[idx] = 2;
                                    else if (r >= 10) grid[idx] = 4;
                                    else grid[idx] = 0;
                                }
                            }
                            grid[5 * COLS + 3] = 3;
                            grid[4 * COLS + 3] = 3;
                            grid[3 * COLS + 3] = 1;
                            grid[5 * COLS + 7] = 4;
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            float w = Math.max(1f, getWidth());
                            float h = Math.max(1f, getHeight());
                            canvas.drawColor(Color.parseColor("#0F172A"));

                            float cellW = w / COLS;
                            float cellH = h / ROWS;

                            for (int r = 0; r < ROWS; r++) {
                                for (int c = 0; c < COLS; c++) {
                                    int block = grid[r * COLS + c];
                                    float left = c * cellW;
                                    float top = r * cellH;
                                    float right = left + cellW;
                                    float bottom = top + cellH;

                                    if (block != 0) {
                                        int topColor = Color.parseColor("#22C55E");
                                        int bodyColor = Color.parseColor("#166534");
                                        if (block == 2) {
                                            topColor = Color.parseColor("#94A3B8");
                                            bodyColor = Color.parseColor("#475569");
                                        } else if (block == 3) {
                                            topColor = Color.parseColor("#D97706");
                                            bodyColor = Color.parseColor("#78350F");
                                        } else if (block == 4) {
                                            topColor = Color.parseColor("#FDE047");
                                            bodyColor = Color.parseColor("#CA8A04");
                                        }
                                        paint.setStyle(Paint.Style.FILL);
                                        paint.setColor(bodyColor);
                                        canvas.drawRect(left + 2f, top + 2f, right - 2f, bottom - 2f, paint);
                                        paint.setColor(topColor);
                                        canvas.drawRect(left + 2f, top + 2f, right - 2f, top + cellH * 0.28f, paint);
                                    } else {
                                        paint.setStyle(Paint.Style.STROKE);
                                        paint.setStrokeWidth(1f);
                                        paint.setColor(Color.parseColor("#1E293B"));
                                        canvas.drawRect(left, top, right, bottom, paint);
                                    }
                                }
                            }

                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(Color.parseColor("#E2E8F0"));
                            paint.setTextSize(26f);
                            paint.setTextAlign(Paint.Align.LEFT);
                            canvas.drawText("Tap grid cells to mine or build voxels", 20f, 38f, paint);
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                                float w = Math.max(1f, getWidth());
                                float h = Math.max(1f, getHeight());
                                int col = (int) ((event.getX() / w) * COLS);
                                int row = (int) ((event.getY() / h) * ROWS);
                                if (col >= 0 && col < COLS && row >= 0 && row < ROWS) {
                                    grid[row * COLS + col] = selectedBlock;
                                    invalidate();
                                }
                                return true;
                            }
                            return super.onTouchEvent(event);
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
    }

    private fun generateKotlinSources(
        template: ProjectTemplate,
        packageName: String,
        pkgPath: String,
        files: MutableMap<String, String>,
    ) {
        when (template) {
            ProjectTemplate.COUNTER_APP -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.graphics.drawable.GradientDrawable
                    import android.os.Bundle
                    import android.view.Gravity
                    import android.widget.Button
                    import android.widget.LinearLayout
                    import android.widget.TextView
                    import android.widget.Toast

                    class MainActivity : Activity() {

                        private var count = 0
                        private lateinit var countDisplay: TextView

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.gravity = Gravity.CENTER
                            root.setPadding(48, 48, 48, 48)
                            root.setBackgroundColor(Color.parseColor("#0F172A"))

                            val title = TextView(this)
                            title.text = getString(R.string.app_name)
                            title.textSize = 26f
                            title.setTypeface(Typeface.DEFAULT_BOLD)
                            title.setTextColor(Color.parseColor("#F8FAFC"))
                            title.gravity = Gravity.CENTER
                            root.addView(title)

                            val subtitle = TextView(this)
                            subtitle.text = getString(R.string.welcome_subtitle)
                            subtitle.textSize = 14f
                            subtitle.setTextColor(Color.parseColor("#94A3B8"))
                            subtitle.gravity = Gravity.CENTER
                            subtitle.setPadding(0, 12, 0, 48)
                            root.addView(subtitle)

                            countDisplay = TextView(this)
                            countDisplay.text = "0"
                            countDisplay.textSize = 64f
                            countDisplay.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            countDisplay.setTextColor(Color.parseColor("#38BDF8"))
                            countDisplay.gravity = Gravity.CENTER
                            countDisplay.setPadding(64, 48, 64, 48)

                            val cardBg = GradientDrawable()
                            cardBg.setColor(Color.parseColor("#1E293B"))
                            cardBg.cornerRadius = 36f
                            cardBg.setStroke(2, Color.parseColor("#334155"))
                            countDisplay.background = cardBg
                            root.addView(countDisplay)

                            val row = LinearLayout(this)
                            row.orientation = LinearLayout.HORIZONTAL
                            row.gravity = Gravity.CENTER
                            row.setPadding(0, 48, 0, 0)

                            val minusBtn = createActionButton("-1", "#334155")
                            minusBtn.setOnClickListener {
                                count--
                                updateCounter()
                            }

                            val resetBtn = createActionButton("Reset", "#475569")
                            resetBtn.setOnClickListener {
                                count = 0
                                updateCounter()
                                Toast.makeText(this@MainActivity, "Counter reset", Toast.LENGTH_SHORT).show()
                            }

                            val plusBtn = createActionButton("+1", "#0284C7")
                            plusBtn.setOnClickListener {
                                count++
                                updateCounter()
                            }

                            row.addView(minusBtn)
                            row.addView(resetBtn)
                            row.addView(plusBtn)
                            root.addView(row)

                            setContentView(root)
                        }

                        private fun createActionButton(label: String, hexColor: String): Button {
                            val btn = Button(this)
                            btn.text = label
                            btn.setTextColor(Color.WHITE)
                            btn.textSize = 16f
                            val bg = GradientDrawable()
                            bg.setColor(Color.parseColor(hexColor))
                            bg.cornerRadius = 24f
                            btn.background = bg
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            lp.setMargins(14, 0, 14, 0)
                            btn.layoutParams = lp
                            btn.setPadding(42, 24, 42, 24)
                            return btn
                        }

                        private fun updateCounter() {
                            countDisplay.text = count.toString()
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.CANVAS_DRAW -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.LinearLayout

                    class MainActivity : Activity() {

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0B0F17"))

                            val pad = DrawingPadView(this)
                            val padParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f
                            )
                            root.addView(pad, padParams)

                            val toolbar = LinearLayout(this)
                            toolbar.orientation = LinearLayout.HORIZONTAL
                            toolbar.setPadding(24, 20, 24, 20)
                            toolbar.setBackgroundColor(Color.parseColor("#182030"))

                            val colors = intArrayOf(
                                Color.parseColor("#38BDF8"),
                                Color.parseColor("#34D399"),
                                Color.parseColor("#FBBF24"),
                                Color.parseColor("#F87171")
                            )
                            val names = arrayOf("Cyan", "Mint", "Gold", "Coral")

                            for (i in 0 until colors.size) {
                                val c = colors[i]
                                val swatch = Button(this)
                                swatch.text = names[i]
                                swatch.setTextColor(Color.BLACK)
                                swatch.setBackgroundColor(c)
                                swatch.setOnClickListener {
                                    pad.setBrushColor(c)
                                }
                                toolbar.addView(swatch)
                            }

                            val clearBtn = Button(this)
                            clearBtn.text = "Clear"
                            clearBtn.setOnClickListener {
                                pad.clearCanvas()
                            }
                            toolbar.addView(clearBtn)

                            root.addView(toolbar)
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/DrawingPadView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.graphics.Path
                    import android.view.MotionEvent
                    import android.view.View

                    class DrawingPadView(context: Context) : View(context) {

                        private class Stroke(val path: Path, val color: Int)

                        private val strokes = mutableListOf<Stroke>()
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        private var activePath: Path? = null
                        private var currentColor = Color.parseColor("#38BDF8")

                        init {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 12f
                            paint.strokeCap = Paint.Cap.ROUND
                            paint.strokeJoin = Paint.Join.ROUND
                        }

                        fun setBrushColor(color: Int) {
                            currentColor = color
                        }

                        fun clearCanvas() {
                            strokes.clear()
                            invalidate()
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            for (s in strokes) {
                                paint.color = s.color
                                canvas.drawPath(s.path, paint)
                            }
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            val x = event.x
                            val y = event.y
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    activePath = Path()
                                    activePath?.moveTo(x, y)
                                    strokes.add(Stroke(activePath!!, currentColor))
                                    invalidate()
                                    return true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (activePath != null) {
                                        activePath?.lineTo(x, y)
                                        invalidate()
                                    }
                                    return true
                                }
                            }
                            return super.onTouchEvent(event)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.CALCULATOR -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.os.Bundle
                    import android.view.Gravity
                    import android.widget.Button
                    import android.widget.GridLayout
                    import android.widget.LinearLayout
                    import android.widget.TextView

                    class MainActivity : Activity() {

                        private lateinit var display: TextView
                        private var firstOperand = 0.0
                        private var pendingOp = ""
                        private var clearOnNextDigit = true

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0B0F17"))
                            root.setPadding(24, 48, 24, 24)

                            display = TextView(this)
                            display.text = "0"
                            display.textSize = 48f
                            display.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            display.setTextColor(Color.WHITE)
                            display.gravity = Gravity.END or Gravity.BOTTOM
                            display.setPadding(24, 48, 24, 36)
                            root.addView(display)

                            val grid = GridLayout(this)
                            grid.columnCount = 4
                            grid.rowCount = 4

                            val keys = arrayOf(
                                "7", "8", "9", "/",
                                "4", "5", "6", "*",
                                "1", "2", "3", "-",
                                "C", "0", "=", "+"
                            )

                            for (key in keys) {
                                val btn = Button(this)
                                btn.text = key
                                btn.textSize = 22f
                                btn.setTextColor(Color.WHITE)
                                val bgColor = if (key.matches(Regex("[0-9]"))) Color.parseColor("#1E293B") else Color.parseColor("#0284C7")
                                btn.setBackgroundColor(bgColor)
                                val params = GridLayout.LayoutParams()
                                params.width = 0
                                params.height = 180
                                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                                params.setMargins(8, 8, 8, 8)
                                btn.layoutParams = params
                                btn.setOnClickListener {
                                    onKeyPress(key)
                                }
                                grid.addView(btn)
                            }

                            root.addView(grid)
                            setContentView(root)
                        }

                        private fun onKeyPress(key: String) {
                            if (key.equals("C")) {
                                firstOperand = 0.0
                                pendingOp = ""
                                clearOnNextDigit = true
                                display.text = "0"
                            } else if (key.matches(Regex("[0-9]"))) {
                                val current = if (clearOnNextDigit) "" else display.text.toString()
                                display.text = current + key
                                clearOnNextDigit = false
                            } else if (key.equals("=")) {
                                val second = display.text.toString().toDouble()
                                var result = second
                                if (pendingOp.equals("+")) result = firstOperand + second
                                else if (pendingOp.equals("-")) result = firstOperand - second
                                else if (pendingOp.equals("*")) result = firstOperand * second
                                else if (pendingOp.equals("/")) result = if (second == 0.0) 0.0 else firstOperand / second
                                val whole = result.toLong()
                                val outText = if (result == whole.toDouble()) "${'$'}whole" else "${'$'}result"
                                display.text = outText
                                pendingOp = ""
                                clearOnNextDigit = true
                            } else {
                                firstOperand = display.text.toString().toDouble()
                                pendingOp = key
                                clearOnNextDigit = true
                            }
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.NOTES_APP -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.graphics.drawable.GradientDrawable
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.EditText
                    import android.widget.LinearLayout
                    import android.widget.ScrollView
                    import android.widget.TextView
                    import android.widget.Toast

                    class MainActivity : Activity() {

                        private lateinit var noteInput: EditText
                        private lateinit var notesContainer: LinearLayout

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0F172A"))
                            root.setPadding(36, 48, 36, 36)

                            val header = TextView(this)
                            header.text = getString(R.string.app_name)
                            header.textSize = 24f
                            header.setTypeface(Typeface.DEFAULT_BOLD)
                            header.setTextColor(Color.WHITE)
                            header.setPadding(0, 0, 0, 24)
                            root.addView(header)

                            noteInput = EditText(this)
                            noteInput.hint = "Write a quick note..."
                            noteInput.setTextColor(Color.WHITE)
                            noteInput.setHintTextColor(Color.parseColor("#94A3B8"))
                            noteInput.setBackgroundColor(Color.parseColor("#1E293B"))
                            noteInput.setPadding(28, 24, 28, 24)
                            root.addView(noteInput)

                            val addBtn = Button(this)
                            addBtn.text = "Save Note"
                            addBtn.setTextColor(Color.WHITE)
                            val btnBg = GradientDrawable()
                            btnBg.setColor(Color.parseColor("#0284C7"))
                            btnBg.cornerRadius = 20f
                            addBtn.background = btnBg
                            val btnLp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            btnLp.setMargins(0, 20, 0, 24)
                            addBtn.layoutParams = btnLp
                            root.addView(addBtn)

                            val scroll = ScrollView(this)
                            notesContainer = LinearLayout(this)
                            notesContainer.orientation = LinearLayout.VERTICAL
                            scroll.addView(notesContainer)
                            root.addView(scroll)

                            addBtn.setOnClickListener {
                                val content = noteInput.text.toString().trim()
                                if (!content.isEmpty()) {
                                    addNoteCard(content)
                                    noteInput.setText("")
                                    Toast.makeText(this@MainActivity, "Note saved", Toast.LENGTH_SHORT).show()
                                }
                            }

                            setContentView(root)
                        }

                        private fun addNoteCard(text: String) {
                            val card = TextView(this)
                            card.text = text
                            card.setTextColor(Color.parseColor("#E2E8F0"))
                            card.textSize = 15f
                            card.setPadding(32, 24, 32, 24)
                            val bg = GradientDrawable()
                            bg.setColor(Color.parseColor("#1E293B"))
                            bg.cornerRadius = 20f
                            card.background = bg
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            lp.setMargins(0, 0, 0, 16)
                            card.layoutParams = lp
                            notesContainer.addView(card, 0)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.STOPWATCH_APP -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.os.Bundle
                    import android.os.Handler
                    import android.os.Looper
                    import android.view.Gravity
                    import android.widget.Button
                    import android.widget.LinearLayout
                    import android.widget.TextView

                    class MainActivity : Activity() {

                        private val handler = Handler(Looper.getMainLooper())
                        private lateinit var readout: TextView
                        private lateinit var lapsView: TextView
                        private var running = false
                        private var elapsedMs = 0L
                        private var lastTickMs = 0L
                        private var lapCount = 0

                        private val ticker = object : Runnable {
                            override fun run() {
                                if (running) {
                                    val now = System.currentTimeMillis()
                                    elapsedMs += (now - lastTickMs)
                                    lastTickMs = now
                                    updateReadout()
                                    handler.postDelayed(this, 80)
                                }
                            }
                        }

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.gravity = Gravity.CENTER_HORIZONTAL
                            root.setBackgroundColor(Color.parseColor("#0B0F17"))
                            root.setPadding(40, 72, 40, 40)

                            readout = TextView(this)
                            readout.text = "00:00.0"
                            readout.textSize = 54f
                            readout.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            readout.setTextColor(Color.parseColor("#34D399"))
                            readout.setPadding(0, 32, 0, 40)
                            root.addView(readout)

                            val controls = LinearLayout(this)
                            controls.orientation = LinearLayout.HORIZONTAL
                            controls.gravity = Gravity.CENTER

                            val toggleBtn = Button(this)
                            toggleBtn.text = "Start / Pause"
                            toggleBtn.setOnClickListener {
                                running = !running
                                if (running) {
                                    lastTickMs = System.currentTimeMillis()
                                    handler.post(ticker)
                                } else {
                                    handler.removeCallbacks(ticker)
                                }
                            }

                            val lapBtn = Button(this)
                            lapBtn.text = "Lap"
                            lapBtn.setOnClickListener {
                                lapCount++
                                val line = "Lap ${'$'}lapCount: ${'$'}{readout.text}\n${'$'}{lapsView.text}"
                                lapsView.text = line
                            }

                            val resetBtn = Button(this)
                            resetBtn.text = "Reset"
                            resetBtn.setOnClickListener {
                                running = false
                                handler.removeCallbacks(ticker)
                                elapsedMs = 0L
                                lapCount = 0
                                lapsView.text = ""
                                updateReadout()
                            }

                            controls.addView(toggleBtn)
                            controls.addView(lapBtn)
                            controls.addView(resetBtn)
                            root.addView(controls)

                            lapsView = TextView(this)
                            lapsView.setTextColor(Color.parseColor("#94A3B8"))
                            lapsView.setTypeface(Typeface.MONOSPACE)
                            lapsView.setPadding(0, 32, 0, 0)
                            root.addView(lapsView)

                            setContentView(root)
                        }

                        private fun updateReadout() {
                            val tenths = (elapsedMs / 100) % 10
                            val totalSecs = elapsedMs / 1000
                            val mins = totalSecs / 60
                            val secs = totalSecs % 60
                            readout.text = String.format("%02d:%02d.%d", mins, secs, tenths)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.ARCADE_SHOOTER -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.os.Bundle

                    class MainActivity : Activity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val gameView = ShooterView(this)
                            setContentView(gameView)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/ShooterView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.graphics.Path
                    import android.os.Handler
                    import android.os.Looper
                    import android.view.MotionEvent
                    import android.view.View
                    import java.util.ArrayList
                    import java.util.Random

                    class ShooterView(context: Context) : View(context) {

                        class Bullet(var x: Float, var y: Float)
                        class Enemy(var x: Float, var y: Float, var vy: Float, var hp: Int, val maxHp: Int)
                        class Star(var x: Float, var y: Float, val speed: Float)

                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        private val handler = Handler(Looper.getMainLooper())
                        private val rng = Random()
                        private val bullets = ArrayList<Bullet>()
                        private val enemies = ArrayList<Enemy>()
                        private val stars = ArrayList<Star>()

                        private var playerX = 450f
                        private var playerY = 1200f
                        private var score = 0
                        private var wave = 1
                        private var lives = 3
                        private var tickCount = 0
                        private var gameOver = false

                        private val loop = object : Runnable {
                            override fun run() {
                                updateFrame()
                                invalidate()
                                handler.postDelayed(this, 16)
                            }
                        }

                        init {
                            for (i in 0 until 42) {
                                stars.add(Star(rng.nextFloat() * 1000f, rng.nextFloat() * 1600f, 1.5f + rng.nextFloat() * 4f))
                            }
                            handler.postDelayed(loop, 16)
                        }

                        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                            super.onSizeChanged(w, h, oldw, oldh)
                            if (w > 0 && h > 0) {
                                playerX = w * 0.5f
                                playerY = h - 130f
                            }
                        }

                        private fun resetGame() {
                            bullets.clear()
                            enemies.clear()
                            score = 0
                            wave = 1
                            lives = 3
                            tickCount = 0
                            gameOver = false
                            playerX = getWidth() * 0.5f
                        }

                        private fun updateFrame() {
                            val w = getWidth()
                            val h = getHeight()
                            if (w <= 0 || h <= 0) return

                            for (i in 0 until stars.size) {
                                val s = stars[i]
                                s.y += s.speed
                                if (s.y > h) {
                                    s.y = 0f
                                    s.x = rng.nextFloat() * w
                                }
                            }

                            if (gameOver) return
                            tickCount++
                            playerY = h - 130f

                            if (tickCount % 10 == 0) {
                                bullets.add(Bullet(playerX - 18f, playerY - 46f))
                                bullets.add(Bullet(playerX + 18f, playerY - 46f))
                            }

                            val spawnInterval = Math.max(18, 46 - wave * 3)
                            if (tickCount % spawnInterval == 0) {
                                val ex = 50f + rng.nextFloat() * Math.max(100f, w - 100f)
                                val hp = 1 + (wave / 2)
                                val vy = 3.2f + wave * 0.55f
                                enemies.add(Enemy(ex, -40f, vy, hp, hp))
                            }

                            for (i in bullets.size - 1 downTo 0) {
                                val b = bullets[i]
                                b.y -= 20f
                                if (b.y < -30f) bullets.removeAt(i)
                            }

                            for (i in enemies.size - 1 downTo 0) {
                                val e = enemies[i]
                                e.y += e.vy
                                if (Math.abs(e.x - playerX) < 48f && Math.abs(e.y - playerY) < 48f) {
                                    enemies.removeAt(i)
                                    lives--
                                    if (lives <= 0) gameOver = true
                                    continue
                                }
                                if (e.y > h + 40f) {
                                    enemies.removeAt(i)
                                    continue
                                }
                                for (j in bullets.size - 1 downTo 0) {
                                    val b = bullets[j]
                                    if (Math.abs(b.x - e.x) < 34f && Math.abs(b.y - e.y) < 34f) {
                                        bullets.removeAt(j)
                                        e.hp--
                                        if (e.hp <= 0) {
                                            score += 100
                                            wave = 1 + score / 800
                                            enemies.removeAt(i)
                                            break
                                        }
                                    }
                                }
                            }
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val w = getWidth()
                            val h = getHeight()
                            canvas.drawColor(Color.parseColor("#060A14"))

                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor("#64748B")
                            for (i in 0 until stars.size) {
                                val s = stars[i]
                                canvas.drawCircle(s.x % Math.max(1, w), s.y % Math.max(1, h), 2.5f, paint)
                            }

                            paint.color = Color.parseColor("#38BDF8")
                            for (i in 0 until bullets.size) {
                                val b = bullets[i]
                                canvas.drawRoundRect(b.x - 4f, b.y - 14f, b.x + 4f, b.y + 14f, 4f, 4f, paint)
                            }

                            for (i in 0 until enemies.size) {
                                val e = enemies[i]
                                paint.color = Color.parseColor("#F43F5E")
                                canvas.drawCircle(e.x, e.y, 26f, paint)
                                paint.color = Color.parseColor("#FDA4AF")
                                canvas.drawCircle(e.x, e.y - 6f, 10f, paint)
                            }

                            val ship = Path()
                            ship.moveTo(playerX, playerY - 40f)
                            ship.lineTo(playerX + 34f, playerY + 26f)
                            ship.lineTo(playerX, playerY + 12f)
                            ship.lineTo(playerX - 34f, playerY + 26f)
                            ship.close()
                            paint.color = Color.parseColor("#22D3EE")
                            canvas.drawPath(ship, paint)

                            paint.color = Color.WHITE
                            paint.textSize = 34f
                            paint.textAlign = Paint.Align.LEFT
                            canvas.drawText("SCORE ${'$'}score   WAVE ${'$'}wave", 28f, 52f, paint)
                            paint.color = Color.parseColor("#F87171")
                            paint.textAlign = Paint.Align.RIGHT
                            canvas.drawText("HP x${'$'}lives", w - 28f, 52f, paint)

                            if (gameOver) {
                                paint.color = Color.parseColor("#CC000000")
                                canvas.drawRect(0f, h * 0.35f, w.toFloat(), h * 0.65f, paint)
                                paint.color = Color.parseColor("#F87171")
                                paint.textAlign = Paint.Align.CENTER
                                paint.textSize = 54f
                                canvas.drawText("MISSION FAILED", w * 0.5f, h * 0.47f, paint)
                                paint.color = Color.WHITE
                                paint.textSize = 32f
                                canvas.drawText("Tap anywhere to launch again", w * 0.5f, h * 0.56f, paint)
                            }
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            if (event.action == MotionEvent.ACTION_DOWN && gameOver) {
                                resetGame()
                                invalidate()
                                return true
                            }
                            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                                playerX = Math.max(40f, Math.min(getWidth() - 40f, event.x))
                                invalidate()
                                return true
                            }
                            return super.onTouchEvent(event)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.VOXEL_LAUNCHER_STUDIO -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.LinearLayout
                    import android.widget.TextView
                    import android.widget.Toast

                    class MainActivity : Activity() {

                        private var javaTarget = 21

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0B1120"))

                            val header = LinearLayout(this)
                            header.orientation = LinearLayout.HORIZONTAL
                            header.setBackgroundColor(Color.parseColor("#111827"))
                            header.setPadding(24, 18, 24, 18)

                            val statusText = TextView(this)
                            statusText.text = "Voxel Sandbox • JVM JDK ${'$'}javaTarget • 512MB Heap"
                            statusText.setTextColor(Color.parseColor("#38BDF8"))
                            statusText.textSize = 13f
                            val statusLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            header.addView(statusText, statusLp)

                            val jvmBtn = Button(this)
                            jvmBtn.text = "Switch JDK"
                            jvmBtn.setTextColor(Color.WHITE)
                            jvmBtn.setBackgroundColor(Color.parseColor("#2563EB"))
                            jvmBtn.setOnClickListener {
                                if (javaTarget == 8) javaTarget = 17
                                else if (javaTarget == 17) javaTarget = 21
                                else if (javaTarget == 21) javaTarget = 25
                                else javaTarget = 8
                                statusText.text = "Voxel Sandbox • JVM JDK ${'$'}javaTarget • Ready"
                                Toast.makeText(this@MainActivity, "Runtime target switched to Java ${'$'}javaTarget", Toast.LENGTH_SHORT).show()
                            }
                            header.addView(jvmBtn)
                            root.addView(header)

                            val worldView = VoxelWorldView(this)
                            val worldLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                            root.addView(worldView, worldLp)

                            val hotbar = LinearLayout(this)
                            hotbar.orientation = LinearLayout.HORIZONTAL
                            hotbar.setBackgroundColor(Color.parseColor("#1E293B"))
                            hotbar.setPadding(16, 12, 16, 12)

                            val blockNames = arrayOf("Mine", "Grass", "Stone", "Timber", "Glow")
                            val hexColors = arrayOf("#475569", "#22C55E", "#64748B", "#B45309", "#FACC15")

                            for (i in 0 until blockNames.size) {
                                val id = i
                                val label = blockNames[i]
                                val slot = Button(this)
                                slot.text = label
                                slot.setTextColor(if (id == 4) Color.BLACK else Color.WHITE)
                                slot.setBackgroundColor(Color.parseColor(hexColors[i]))
                                val slotLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                slotLp.setMargins(6, 0, 6, 0)
                                slot.layoutParams = slotLp
                                slot.setOnClickListener {
                                    worldView.setSelectedBlock(id)
                                    Toast.makeText(this@MainActivity, "Tool: ${'$'}label", Toast.LENGTH_SHORT).show()
                                }
                                hotbar.addView(slot)
                            }

                            root.addView(hotbar)
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/VoxelWorldView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.view.MotionEvent
                    import android.view.View

                    class VoxelWorldView(context: Context) : View(context) {

                        private val cols = 10
                        private val rows = 12
                        private val grid = IntArray(cols * rows)
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        private var selectedBlock = 1

                        init {
                            generateTerrain()
                        }

                        fun setSelectedBlock(blockId: Int) {
                            selectedBlock = blockId
                            invalidate()
                        }

                        private fun generateTerrain() {
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val idx = r * cols + c
                                    if (r == 6) grid[idx] = 1
                                    else if (r > 6 && r < 10) grid[idx] = 2
                                    else if (r >= 10) grid[idx] = 4
                                    else grid[idx] = 0
                                }
                            }
                            grid[5 * cols + 3] = 3
                            grid[4 * cols + 3] = 3
                            grid[3 * cols + 3] = 1
                            grid[5 * cols + 7] = 4
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val w = Math.max(1f, getWidth().toFloat())
                            val h = Math.max(1f, getHeight().toFloat())
                            canvas.drawColor(Color.parseColor("#0F172A"))

                            val cellW = w / cols
                            val cellH = h / rows

                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val block = grid[r * cols + c]
                                    val left = c * cellW
                                    val top = r * cellH
                                    val right = left + cellW
                                    val bottom = top + cellH

                                    if (block != 0) {
                                        var topColor = Color.parseColor("#22C55E")
                                        var bodyColor = Color.parseColor("#166534")
                                        if (block == 2) {
                                            topColor = Color.parseColor("#94A3B8")
                                            bodyColor = Color.parseColor("#475569")
                                        } else if (block == 3) {
                                            topColor = Color.parseColor("#D97706")
                                            bodyColor = Color.parseColor("#78350F")
                                        } else if (block == 4) {
                                            topColor = Color.parseColor("#FDE047")
                                            bodyColor = Color.parseColor("#CA8A04")
                                        }
                                        paint.style = Paint.Style.FILL
                                        paint.color = bodyColor
                                        canvas.drawRect(left + 2f, top + 2f, right - 2f, bottom - 2f, paint)
                                        paint.color = topColor
                                        canvas.drawRect(left + 2f, top + 2f, right - 2f, top + cellH * 0.28f, paint)
                                    } else {
                                        paint.style = Paint.Style.STROKE
                                        paint.strokeWidth = 1f
                                        paint.color = Color.parseColor("#1E293B")
                                        canvas.drawRect(left, top, right, bottom, paint)
                                    }
                                }
                            }

                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor("#E2E8F0")
                            paint.textSize = 26f
                            paint.textAlign = Paint.Align.LEFT
                            canvas.drawText("Tap grid cells to mine or build voxels", 20f, 38f, paint)
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                                val w = Math.max(1f, getWidth().toFloat())
                                val h = Math.max(1f, getHeight().toFloat())
                                val col = ((event.x / w) * cols).toInt()
                                val row = ((event.y / h) * rows).toInt()
                                if (col >= 0 && col < cols && row >= 0 && row < rows) {
                                    grid[row * cols + col] = selectedBlock
                                    invalidate()
                                }
                                return true
                            }
                            return super.onTouchEvent(event)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.BLANK_ACTIVITY -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.os.Bundle
                    import android.view.Gravity
                    import android.widget.TextView

                    class MainActivity : Activity() {

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)

                            val tv = TextView(this)
                            tv.text = "Hello from ${'$'}{getString(R.string.app_name)}!"
                            tv.textSize = 22f
                            tv.gravity = Gravity.CENTER
                            setContentView(tv)
                        }
                    }
                """.trimIndent() + "\n"
            }
        }
    }

    private fun escapeXml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
