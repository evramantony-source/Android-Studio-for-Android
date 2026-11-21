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
                <string name="welcome_subtitle">Built offline with Android Studio: For Android</string>
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

            ProjectTemplate.SNAKE_ARCADE -> {
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

                            final SnakeGameView game = new SnakeGameView(this);
                            root.addView(game, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

                            LinearLayout pad = new LinearLayout(this);
                            pad.setOrientation(LinearLayout.HORIZONTAL);
                            pad.setBackgroundColor(Color.parseColor("#111827"));
                            pad.setPadding(16, 12, 16, 12);

                            String[] labels = {"LEFT", "UP", "DOWN", "RIGHT", "RESET"};
                            for (int i = 0; i < labels.length; i++) {
                                final int idx = i;
                                Button b = new Button(this);
                                b.setText(labels[i]);
                                b.setTextColor(Color.WHITE);
                                b.setBackgroundColor(idx == 4 ? Color.parseColor("#DC2626") : Color.parseColor("#1E293B"));
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                                lp.setMargins(4, 0, 4, 0);
                                b.setLayoutParams(lp);
                                b.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        if (idx == 0) game.setDirection(-1, 0);
                                        else if (idx == 1) game.setDirection(0, -1);
                                        else if (idx == 2) game.setDirection(0, 1);
                                        else if (idx == 3) game.setDirection(1, 0);
                                        else game.resetGame();
                                    }
                                });
                                pad.addView(b);
                            }
                            root.addView(pad);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/SnakeGameView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.os.Handler;
                    import android.os.Looper;
                    import android.view.MotionEvent;
                    import android.view.View;
                    import java.util.ArrayList;
                    import java.util.Random;

                    public class SnakeGameView extends View {
                        private static final int GRID = 16;
                        private final ArrayList<Integer> bodyX = new ArrayList<Integer>();
                        private final ArrayList<Integer> bodyY = new ArrayList<Integer>();
                        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private final Handler handler = new Handler(Looper.getMainLooper());
                        private final Random rng = new Random();
                        private int dirX = 1;
                        private int dirY = 0;
                        private int foodX = 10;
                        private int foodY = 8;
                        private int score = 0;
                        private int best = 0;
                        private boolean gameOver = false;
                        private float downX = 0f;
                        private float downY = 0f;

                        private final Runnable ticker = new Runnable() {
                            @Override
                            public void run() {
                                stepGame();
                                invalidate();
                                handler.postDelayed(this, 160);
                            }
                        };

                        public SnakeGameView(Context context) {
                            super(context);
                            resetGame();
                            handler.postDelayed(ticker, 160);
                        }

                        public void setDirection(int dx, int dy) {
                            if (dx == -dirX && dy == -dirY) return;
                            dirX = dx;
                            dirY = dy;
                        }

                        public void resetGame() {
                            bodyX.clear();
                            bodyY.clear();
                            bodyX.add(6); bodyY.add(8);
                            bodyX.add(5); bodyY.add(8);
                            bodyX.add(4); bodyY.add(8);
                            dirX = 1;
                            dirY = 0;
                            score = 0;
                            gameOver = false;
                            spawnFood();
                            invalidate();
                        }

                        private void spawnFood() {
                            foodX = rng.nextInt(GRID);
                            foodY = rng.nextInt(GRID);
                        }

                        private void stepGame() {
                            if (gameOver) return;
                            int nx = bodyX.get(0) + dirX;
                            int ny = bodyY.get(0) + dirY;
                            if (nx < 0 || ny < 0 || nx >= GRID || ny >= GRID) {
                                gameOver = true;
                                return;
                            }
                            for (int i = 0; i < bodyX.size(); i++) {
                                if (bodyX.get(i) == nx && bodyY.get(i) == ny) {
                                    gameOver = true;
                                    return;
                                }
                            }
                            bodyX.add(0, nx);
                            bodyY.add(0, ny);
                            if (nx == foodX && ny == foodY) {
                                score += 10;
                                if (score > best) best = score;
                                spawnFood();
                            } else {
                                bodyX.remove(bodyX.size() - 1);
                                bodyY.remove(bodyY.size() - 1);
                            }
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            int w = Math.max(1, getWidth());
                            int h = Math.max(1, getHeight());
                            canvas.drawColor(Color.parseColor("#0B0F17"));

                            float board = Math.min(w - 32f, h - 100f);
                            float cell = board / GRID;
                            float ox = (w - board) * 0.5f;
                            float oy = 76f;

                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(Color.parseColor("#111827"));
                            canvas.drawRect(ox, oy, ox + board, oy + board, paint);

                            paint.setColor(Color.parseColor("#F43F5E"));
                            canvas.drawCircle(ox + (foodX + 0.5f) * cell, oy + (foodY + 0.5f) * cell, cell * 0.38f, paint);

                            for (int i = 0; i < bodyX.size(); i++) {
                                paint.setColor(i == 0 ? Color.parseColor("#4ADE80") : Color.parseColor("#22C55E"));
                                float l = ox + bodyX.get(i) * cell + 2f;
                                float t = oy + bodyY.get(i) * cell + 2f;
                                canvas.drawRoundRect(l, t, l + cell - 4f, t + cell - 4f, 6f, 6f, paint);
                            }

                            paint.setColor(Color.WHITE);
                            paint.setTextSize(32f);
                            paint.setTextAlign(Paint.Align.LEFT);
                            canvas.drawText("SCORE " + score + "   BEST " + best, ox, 48f, paint);

                            if (gameOver) {
                                paint.setColor(Color.parseColor("#CC000000"));
                                canvas.drawRect(ox, oy + board * 0.35f, ox + board, oy + board * 0.65f, paint);
                                paint.setColor(Color.parseColor("#F87171"));
                                paint.setTextAlign(Paint.Align.CENTER);
                                paint.setTextSize(42f);
                                canvas.drawText("GAME OVER", ox + board * 0.5f, oy + board * 0.50f, paint);
                            }
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                                if (gameOver) { resetGame(); return true; }
                                downX = event.getX();
                                downY = event.getY();
                                return true;
                            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                                float dx = event.getX() - downX;
                                float dy = event.getY() - downY;
                                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 24f) {
                                    setDirection(dx > 0 ? 1 : -1, 0);
                                } else if (Math.abs(dy) > 24f) {
                                    setDirection(0, dy > 0 ? 1 : -1);
                                }
                                return true;
                            }
                            return super.onTouchEvent(event);
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.MUSIC_SYNTH_PAD -> {
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
                    import android.widget.GridLayout;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {
                        private final Handler handler = new Handler(Looper.getMainLooper());
                        private TextView statusBanner;
                        private int bpm = 120;
                        private int currentStep = 0;
                        private boolean playing = false;

                        private final Runnable stepLoop = new Runnable() {
                            @Override
                            public void run() {
                                if (!playing) return;
                                currentStep = (currentStep + 1) % 8;
                                statusBanner.setText("PLAYING • STEP " + (currentStep + 1) + "/8 • " + bpm + " BPM");
                                handler.postDelayed(this, Math.max(120, 15000 / bpm));
                            }
                        };

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0F172A"));
                            root.setPadding(24, 36, 24, 24);

                            statusBanner = new TextView(this);
                            statusBanner.setText("BEAT STUDIO READY • 120 BPM");
                            statusBanner.setTextColor(Color.parseColor("#38BDF8"));
                            statusBanner.setTextSize(16);
                            statusBanner.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            statusBanner.setGravity(Gravity.CENTER);
                            statusBanner.setPadding(16, 24, 16, 24);
                            statusBanner.setBackgroundColor(Color.parseColor("#1E293B"));
                            root.addView(statusBanner);

                            GridLayout grid = new GridLayout(this);
                            grid.setColumnCount(3);
                            grid.setPadding(0, 24, 0, 24);

                            final String[] pads = {"KICK", "SNARE", "HI-HAT", "CLAP", "808 BASS", "CHORD", "ARP C4", "SYNTH E4", "BELL G4"};
                            final String[] padColors = {"#EF4444", "#F97316", "#FACC15", "#22C55E", "#14B8A6", "#38BDF8", "#6366F1", "#A855F7", "#EC4899"};

                            for (int i = 0; i < pads.length; i++) {
                                final String label = pads[i];
                                Button padBtn = new Button(this);
                                padBtn.setText(label);
                                padBtn.setTextColor(Color.WHITE);
                                padBtn.setBackgroundColor(Color.parseColor(padColors[i]));
                                GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
                                gp.width = 0;
                                gp.height = 170;
                                gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
                                gp.setMargins(8, 8, 8, 8);
                                padBtn.setLayoutParams(gp);
                                padBtn.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        statusBanner.setText("TRIGGERED: " + label + " • " + bpm + " BPM");
                                    }
                                });
                                grid.addView(padBtn);
                            }
                            root.addView(grid);

                            LinearLayout transport = new LinearLayout(this);
                            transport.setOrientation(LinearLayout.HORIZONTAL);
                            Button playBtn = new Button(this);
                            playBtn.setText("Start / Stop");
                            playBtn.setTextColor(Color.WHITE);
                            playBtn.setBackgroundColor(Color.parseColor("#10B981"));
                            playBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    playing = !playing;
                                    if (playing) {
                                        handler.post(stepLoop);
                                    } else {
                                        handler.removeCallbacks(stepLoop);
                                        statusBanner.setText("PAUSED • " + bpm + " BPM");
                                    }
                                }
                            });
                            Button tempoUp = new Button(this);
                            tempoUp.setText("+10 BPM");
                            tempoUp.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    bpm = Math.min(200, bpm + 10);
                                    statusBanner.setText("TEMPO: " + bpm + " BPM");
                                }
                            });
                            transport.addView(playBtn);
                            transport.addView(tempoUp);
                            root.addView(transport);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.EXPENSE_TRACKER -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.EditText;
                    import android.widget.LinearLayout;
                    import android.widget.ScrollView;
                    import android.widget.TextView;
                    import android.widget.Toast;

                    public class MainActivity extends Activity {
                        private double balance = 1250.0;
                        private TextView balanceLabel;
                        private EditText descInput;
                        private EditText amountInput;
                        private LinearLayout ledgerList;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0F172A"));
                            root.setPadding(28, 36, 28, 28);

                            balanceLabel = new TextView(this);
                            balanceLabel.setText("Balance: $1250.00");
                            balanceLabel.setTextSize(28);
                            balanceLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            balanceLabel.setTextColor(Color.parseColor("#34D399"));
                            balanceLabel.setPadding(24, 24, 24, 24);
                            balanceLabel.setBackgroundColor(Color.parseColor("#1E293B"));
                            root.addView(balanceLabel);

                            descInput = new EditText(this);
                            descInput.setHint("Category or item (e.g. Cloud Hosting)");
                            descInput.setTextColor(Color.WHITE);
                            descInput.setHintTextColor(Color.parseColor("#94A3B8"));
                            root.addView(descInput);

                            amountInput = new EditText(this);
                            amountInput.setHint("Amount (e.g. 24.50)");
                            amountInput.setTextColor(Color.WHITE);
                            amountInput.setHintTextColor(Color.parseColor("#94A3B8"));
                            root.addView(amountInput);

                            LinearLayout row = new LinearLayout(this);
                            row.setOrientation(LinearLayout.HORIZONTAL);
                            Button expenseBtn = new Button(this);
                            expenseBtn.setText("- Expense");
                            expenseBtn.setTextColor(Color.WHITE);
                            expenseBtn.setBackgroundColor(Color.parseColor("#EF4444"));
                            expenseBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) { recordEntry(false); }
                            });
                            Button incomeBtn = new Button(this);
                            incomeBtn.setText("+ Income");
                            incomeBtn.setTextColor(Color.WHITE);
                            incomeBtn.setBackgroundColor(Color.parseColor("#10B981"));
                            incomeBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) { recordEntry(true); }
                            });
                            row.addView(expenseBtn);
                            row.addView(incomeBtn);
                            root.addView(row);

                            ScrollView scroll = new ScrollView(this);
                            ledgerList = new LinearLayout(this);
                            ledgerList.setOrientation(LinearLayout.VERTICAL);
                            scroll.addView(ledgerList);
                            root.addView(scroll);
                            setContentView(root);
                        }

                        private void recordEntry(boolean isIncome) {
                            String label = descInput.getText().toString().trim();
                            if (label.isEmpty()) label = isIncome ? "Deposit" : "Purchase";
                            double amt = 10.0;
                            try {
                                amt = Math.abs(Double.parseDouble(amountInput.getText().toString().trim()));
                            } catch (Exception ignored) { }
                            balance += isIncome ? amt : -amt;
                            balanceLabel.setText(String.format("Balance: $%.2f", balance));
                            TextView item = new TextView(this);
                            item.setText((isIncome ? "+ $" : "- $") + String.format("%.2f", amt) + "  •  " + label);
                            item.setTextColor(isIncome ? Color.parseColor("#34D399") : Color.parseColor("#FCA5A5"));
                            item.setPadding(16, 14, 16, 14);
                            ledgerList.addView(item, 0);
                            descInput.setText("");
                            amountInput.setText("");
                            Toast.makeText(this, "Entry logged", Toast.LENGTH_SHORT).show();
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.QUIZ_STUDIO -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;
                    import android.widget.Toast;

                    public class MainActivity extends Activity {
                        private final String[] questions = {
                            "Which file declares an Android app's launcher Activity?",
                            "Which bytecode format runs on Android ART?",
                            "Which layout arranges child views vertically or horizontally?",
                            "What signs an APK cleanly with v1/v2/v3 schemes?"
                        };
                        private final String[] answers = {
                            "AndroidManifest.xml",
                            "DEX (Dalvik Executable)",
                            "LinearLayout",
                            "ApkSigner"
                        };
                        private final String[][] options = {
                            {"AndroidManifest.xml", "build.gradle", "strings.xml", "styles.xml"},
                            {"DEX (Dalvik Executable)", "ELF64", "WASM", "MSIL"},
                            {"LinearLayout", "RecyclerView", "SurfaceView", "IntentFilter"},
                            {"ApkSigner", "ZipAligner", "DexMerge", "Aapt"}
                        };
                        private int idx = 0;
                        private int score = 0;
                        private int streak = 0;
                        private TextView scoreView;
                        private TextView questionView;
                        private final Button[] optBtns = new Button[4];

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0B1120"));
                            root.setPadding(32, 48, 32, 32);

                            scoreView = new TextView(this);
                            scoreView.setTextColor(Color.parseColor("#38BDF8"));
                            scoreView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                            scoreView.setTextSize(16);
                            root.addView(scoreView);

                            questionView = new TextView(this);
                            questionView.setTextColor(Color.WHITE);
                            questionView.setTextSize(20);
                            questionView.setTypeface(Typeface.DEFAULT_BOLD);
                            questionView.setPadding(0, 24, 0, 32);
                            root.addView(questionView);

                            for (int i = 0; i < 4; i++) {
                                final int choice = i;
                                Button b = new Button(this);
                                b.setTextColor(Color.WHITE);
                                b.setBackgroundColor(Color.parseColor("#1E293B"));
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                                );
                                lp.setMargins(0, 10, 0, 10);
                                b.setLayoutParams(lp);
                                b.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) { answerQuestion(choice); }
                                });
                                optBtns[i] = b;
                                root.addView(b);
                            }
                            renderQuestion();
                            setContentView(root);
                        }

                        private void renderQuestion() {
                            int qIdx = idx % questions.length;
                            scoreView.setText("SCORE: " + score + "   STREAK: x" + streak + "   Q" + (qIdx + 1) + "/" + questions.length);
                            questionView.setText(questions[qIdx]);
                            for (int i = 0; i < 4; i++) {
                                optBtns[i].setText(options[qIdx][i]);
                            }
                        }

                        private void answerQuestion(int choice) {
                            int qIdx = idx % questions.length;
                            String picked = options[qIdx][choice];
                            if (picked.equals(answers[qIdx])) {
                                streak++;
                                score += 100 * streak;
                                Toast.makeText(this, "Correct! +" + (100 * streak), Toast.LENGTH_SHORT).show();
                            } else {
                                streak = 0;
                                Toast.makeText(this, "Answer: " + answers[qIdx], Toast.LENGTH_SHORT).show();
                            }
                            idx++;
                            renderQuestion();
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.WEATHER_DASHBOARD -> {
                files["src/$pkgPath/MainActivity.java"] = """
                    package $packageName;

                    import android.app.Activity;
                    import android.graphics.Color;
                    import android.graphics.Typeface;
                    import android.os.Bundle;
                    import android.view.View;
                    import android.widget.Button;
                    import android.widget.LinearLayout;
                    import android.widget.TextView;

                    public class MainActivity extends Activity {
                        private TextView cityTitle;
                        private TextView tempView;
                        private TextView metaView;
                        private ForecastChartView chartView;

                        @Override
                        protected void onCreate(Bundle savedInstanceState) {
                            super.onCreate(savedInstanceState);
                            LinearLayout root = new LinearLayout(this);
                            root.setOrientation(LinearLayout.VERTICAL);
                            root.setBackgroundColor(Color.parseColor("#0B1324"));
                            root.setPadding(28, 40, 28, 28);

                            LinearLayout cityBar = new LinearLayout(this);
                            cityBar.setOrientation(LinearLayout.HORIZONTAL);
                            final String[] cities = {"Tokyo", "London", "New York", "Cairo"};
                            final int[] temps = {23, 15, 19, 31};
                            final String[] conds = {"Clear • Wind 11 km/h", "Light Mist • Wind 18 km/h", "Partly Cloudy • Wind 14 km/h", "Sunny • Wind 9 km/h"};

                            cityTitle = new TextView(this);
                            cityTitle.setTextColor(Color.parseColor("#93C5FD"));
                            cityTitle.setTextSize(18);
                            cityTitle.setPadding(0, 24, 0, 8);

                            tempView = new TextView(this);
                            tempView.setTextColor(Color.WHITE);
                            tempView.setTextSize(48);
                            tempView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

                            metaView = new TextView(this);
                            metaView.setTextColor(Color.parseColor("#94A3B8"));
                            metaView.setPadding(0, 8, 0, 24);

                            chartView = new ForecastChartView(this);

                            for (int i = 0; i < cities.length; i++) {
                                final int idx = i;
                                Button b = new Button(this);
                                b.setText(cities[i]);
                                b.setTextColor(Color.WHITE);
                                b.setBackgroundColor(Color.parseColor("#1E293B"));
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                                lp.setMargins(4, 0, 4, 0);
                                b.setLayoutParams(lp);
                                b.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        cityTitle.setText(cities[idx] + " Weather Station");
                                        tempView.setText(temps[idx] + "°C");
                                        metaView.setText(conds[idx]);
                                        chartView.setBaseTemp(temps[idx]);
                                    }
                                });
                                cityBar.addView(b);
                            }

                            root.addView(cityBar);
                            root.addView(cityTitle);
                            root.addView(tempView);
                            root.addView(metaView);
                            root.addView(chartView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

                            cityTitle.setText(cities[0] + " Weather Station");
                            tempView.setText(temps[0] + "°C");
                            metaView.setText(conds[0]);
                            chartView.setBaseTemp(temps[0]);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/ForecastChartView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.view.View;

                    public class ForecastChartView extends View {
                        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        private int baseTemp = 23;

                        public ForecastChartView(Context context) {
                            super(context);
                        }

                        public void setBaseTemp(int t) {
                            baseTemp = t;
                            invalidate();
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            float w = Math.max(1f, getWidth());
                            float h = Math.max(1f, getHeight());
                            canvas.drawColor(Color.parseColor("#111827"));

                            int[] offsets = {-3, -2, 0, 2, 3, 1, -1};
                            float stepX = w / (offsets.length + 1);
                            paint.setStyle(Paint.Style.STROKE);
                            paint.setStrokeWidth(6f);
                            paint.setColor(Color.parseColor("#38BDF8"));

                            float prevX = 0f;
                            float prevY = 0f;
                            for (int i = 0; i < offsets.length; i++) {
                                float x = stepX * (i + 1);
                                float y = h * 0.55f - offsets[i] * 26f;
                                if (i > 0) canvas.drawLine(prevX, prevY, x, y, paint);
                                prevX = x;
                                prevY = y;
                            }

                            paint.setStyle(Paint.Style.FILL);
                            for (int i = 0; i < offsets.length; i++) {
                                float x = stepX * (i + 1);
                                float y = h * 0.55f - offsets[i] * 26f;
                                paint.setColor(Color.parseColor("#FBBF24"));
                                canvas.drawCircle(x, y, 10f, paint);
                                paint.setColor(Color.WHITE);
                                paint.setTextSize(26f);
                                paint.setTextAlign(Paint.Align.CENTER);
                                canvas.drawText((baseTemp + offsets[i]) + "°", x, y - 20f, paint);
                            }
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.PIXEL_SPRITE_EDITOR -> {
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

                            final PixelCanvasView canvasView = new PixelCanvasView(this);
                            root.addView(canvasView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

                            LinearLayout palette = new LinearLayout(this);
                            palette.setOrientation(LinearLayout.HORIZONTAL);
                            palette.setBackgroundColor(Color.parseColor("#182030"));
                            palette.setPadding(12, 12, 12, 12);

                            final int[] swatches = {
                                Color.parseColor("#38BDF8"),
                                Color.parseColor("#22C55E"),
                                Color.parseColor("#FACC15"),
                                Color.parseColor("#F43F5E"),
                                Color.parseColor("#FFFFFF")
                            };
                            final String[] names = {"Sky", "Lime", "Gold", "Rose", "White"};
                            for (int i = 0; i < swatches.length; i++) {
                                final int c = swatches[i];
                                Button b = new Button(this);
                                b.setText(names[i]);
                                b.setTextColor(Color.BLACK);
                                b.setBackgroundColor(c);
                                b.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) { canvasView.setBrushColor(c); }
                                });
                                palette.addView(b);
                            }
                            Button clearBtn = new Button(this);
                            clearBtn.setText("Clear");
                            clearBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) { canvasView.clearGrid(); }
                            });
                            palette.addView(clearBtn);

                            root.addView(palette);
                            setContentView(root);
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/PixelCanvasView.java"] = """
                    package $packageName;

                    import android.content.Context;
                    import android.graphics.Canvas;
                    import android.graphics.Color;
                    import android.graphics.Paint;
                    import android.view.MotionEvent;
                    import android.view.View;

                    public class PixelCanvasView extends View {
                        private static final int SIZE = 12;
                        private final int[] pixels = new int[SIZE * SIZE];
                        private final Paint paint = new Paint();
                        private int activeColor = Color.parseColor("#38BDF8");

                        public PixelCanvasView(Context context) {
                            super(context);
                            clearGrid();
                        }

                        public void setBrushColor(int color) {
                            activeColor = color;
                        }

                        public void clearGrid() {
                            for (int i = 0; i < pixels.length; i++) {
                                pixels[i] = 0;
                            }
                            invalidate();
                        }

                        @Override
                        protected void onDraw(Canvas canvas) {
                            super.onDraw(canvas);
                            float board = Math.min(getWidth(), getHeight()) - 32f;
                            float cell = board / SIZE;
                            float ox = (getWidth() - board) * 0.5f;
                            float oy = (getHeight() - board) * 0.5f;
                            canvas.drawColor(Color.parseColor("#0B0F17"));

                            for (int r = 0; r < SIZE; r++) {
                                for (int c = 0; c < SIZE; c++) {
                                    int col = pixels[r * SIZE + c];
                                    float l = ox + c * cell;
                                    float t = oy + r * cell;
                                    paint.setStyle(Paint.Style.FILL);
                                    paint.setColor(col == 0 ? Color.parseColor("#111827") : col);
                                    canvas.drawRect(l + 1f, t + 1f, l + cell - 1f, t + cell - 1f, paint);
                                    paint.setStyle(Paint.Style.STROKE);
                                    paint.setColor(Color.parseColor("#1E293B"));
                                    canvas.drawRect(l, t, l + cell, t + cell, paint);
                                }
                            }
                        }

                        @Override
                        public boolean onTouchEvent(MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                                float board = Math.min(getWidth(), getHeight()) - 32f;
                                float ox = (getWidth() - board) * 0.5f;
                                float oy = (getHeight() - board) * 0.5f;
                                int c = (int) (((event.getX() - ox) / board) * SIZE);
                                int r = (int) (((event.getY() - oy) / board) * SIZE);
                                if (c >= 0 && c < SIZE && r >= 0 && r < SIZE) {
                                    pixels[r * SIZE + c] = activeColor;
                                    invalidate();
                                }
                                return true;
                            }
                            return super.onTouchEvent(event);
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

            ProjectTemplate.SNAKE_ARCADE -> {
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

                            val game = SnakeGameView(this)
                            val gameLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                            root.addView(game, gameLp)

                            val pad = LinearLayout(this)
                            pad.orientation = LinearLayout.HORIZONTAL
                            pad.setBackgroundColor(Color.parseColor("#111827"))
                            pad.setPadding(16, 12, 16, 12)

                            val labels = arrayOf("LEFT", "UP", "DOWN", "RIGHT", "RESET")
                            for (i in 0 until labels.size) {
                                val idx = i
                                val b = Button(this)
                                b.text = labels[i]
                                b.setTextColor(Color.WHITE)
                                b.setBackgroundColor(if (idx == 4) Color.parseColor("#DC2626") else Color.parseColor("#1E293B"))
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                lp.setMargins(4, 0, 4, 0)
                                b.layoutParams = lp
                                b.setOnClickListener {
                                    if (idx == 0) game.setDirection(-1, 0)
                                    else if (idx == 1) game.setDirection(0, -1)
                                    else if (idx == 2) game.setDirection(0, 1)
                                    else if (idx == 3) game.setDirection(1, 0)
                                    else game.resetGame()
                                }
                                pad.addView(b)
                            }
                            root.addView(pad)
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/SnakeGameView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.os.Handler
                    import android.os.Looper
                    import android.view.MotionEvent
                    import android.view.View
                    import java.util.ArrayList
                    import java.util.Random

                    class SnakeGameView(context: Context) : View(context) {
                        private val gridSize = 16
                        private val bodyX = ArrayList<Int>()
                        private val bodyY = ArrayList<Int>()
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        private val handler = Handler(Looper.getMainLooper())
                        private val rng = Random()
                        private var dirX = 1
                        private var dirY = 0
                        private var foodX = 10
                        private var foodY = 8
                        private var score = 0
                        private var best = 0
                        private var gameOver = false
                        private var downX = 0f
                        private var downY = 0f

                        private val ticker = object : Runnable {
                            override fun run() {
                                stepGame()
                                invalidate()
                                handler.postDelayed(this, 160)
                            }
                        }

                        init {
                            resetGame()
                            handler.postDelayed(ticker, 160)
                        }

                        fun setDirection(dx: Int, dy: Int) {
                            if (dx == -dirX && dy == -dirY) return
                            dirX = dx
                            dirY = dy
                        }

                        fun resetGame() {
                            bodyX.clear()
                            bodyY.clear()
                            bodyX.add(6)
                            bodyY.add(8)
                            bodyX.add(5)
                            bodyY.add(8)
                            bodyX.add(4)
                            bodyY.add(8)
                            dirX = 1
                            dirY = 0
                            score = 0
                            gameOver = false
                            spawnFood()
                            invalidate()
                        }

                        private fun spawnFood() {
                            foodX = rng.nextInt(gridSize)
                            foodY = rng.nextInt(gridSize)
                        }

                        private fun stepGame() {
                            if (gameOver) return
                            val nx = bodyX[0] + dirX
                            val ny = bodyY[0] + dirY
                            if (nx < 0 || ny < 0 || nx >= gridSize || ny >= gridSize) {
                                gameOver = true
                                return
                            }
                            for (i in 0 until bodyX.size) {
                                if (bodyX[i] == nx && bodyY[i] == ny) {
                                    gameOver = true
                                    return
                                }
                            }
                            bodyX.add(0, nx)
                            bodyY.add(0, ny)
                            if (nx == foodX && ny == foodY) {
                                score += 10
                                if (score > best) best = score
                                spawnFood()
                            } else {
                                bodyX.removeAt(bodyX.size - 1)
                                bodyY.removeAt(bodyY.size - 1)
                            }
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val w = Math.max(1, getWidth())
                            val h = Math.max(1, getHeight())
                            canvas.drawColor(Color.parseColor("#0B0F17"))

                            val board = Math.min(w - 32f, h - 100f)
                            val cell = board / gridSize
                            val ox = (w - board) * 0.5f
                            val oy = 76f

                            paint.style = Paint.Style.FILL
                            paint.color = Color.parseColor("#111827")
                            canvas.drawRect(ox, oy, ox + board, oy + board, paint)

                            paint.color = Color.parseColor("#F43F5E")
                            canvas.drawCircle(ox + (foodX + 0.5f) * cell, oy + (foodY + 0.5f) * cell, cell * 0.38f, paint)

                            for (i in 0 until bodyX.size) {
                                paint.color = if (i == 0) Color.parseColor("#4ADE80") else Color.parseColor("#22C55E")
                                val l = ox + bodyX[i] * cell + 2f
                                val t = oy + bodyY[i] * cell + 2f
                                canvas.drawRoundRect(l, t, l + cell - 4f, t + cell - 4f, 6f, 6f, paint)
                            }

                            paint.color = Color.WHITE
                            paint.textSize = 32f
                            paint.textAlign = Paint.Align.LEFT
                            canvas.drawText("SCORE ${'$'}score   BEST ${'$'}best", ox, 48f, paint)

                            if (gameOver) {
                                paint.color = Color.parseColor("#CC000000")
                                canvas.drawRect(ox, oy + board * 0.35f, ox + board, oy + board * 0.65f, paint)
                                paint.color = Color.parseColor("#F87171")
                                paint.textAlign = Paint.Align.CENTER
                                paint.textSize = 42f
                                canvas.drawText("GAME OVER", ox + board * 0.5f, oy + board * 0.50f, paint)
                            }
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                if (gameOver) {
                                    resetGame()
                                    return true
                                }
                                downX = event.x
                                downY = event.y
                                return true
                            } else if (event.action == MotionEvent.ACTION_UP) {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 24f) {
                                    setDirection(if (dx > 0) 1 else -1, 0)
                                } else if (Math.abs(dy) > 24f) {
                                    setDirection(0, if (dy > 0) 1 else -1)
                                }
                                return true
                            }
                            return super.onTouchEvent(event)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.MUSIC_SYNTH_PAD -> {
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
                    import android.widget.GridLayout
                    import android.widget.LinearLayout
                    import android.widget.TextView

                    class MainActivity : Activity() {
                        private val handler = Handler(Looper.getMainLooper())
                        private lateinit var statusBanner: TextView
                        private var bpm = 120
                        private var currentStep = 0
                        private var playing = false

                        private val stepLoop = object : Runnable {
                            override fun run() {
                                if (playing) {
                                    currentStep = (currentStep + 1) % 8
                                    statusBanner.text = "PLAYING • STEP ${'$'}{currentStep + 1}/8 • ${'$'}bpm BPM"
                                    handler.postDelayed(this, Math.max(120, 15000 / bpm).toLong())
                                }
                            }
                        }

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0F172A"))
                            root.setPadding(24, 36, 24, 24)

                            statusBanner = TextView(this)
                            statusBanner.text = "BEAT STUDIO READY • 120 BPM"
                            statusBanner.setTextColor(Color.parseColor("#38BDF8"))
                            statusBanner.textSize = 16f
                            statusBanner.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            statusBanner.gravity = Gravity.CENTER
                            statusBanner.setPadding(16, 24, 16, 24)
                            statusBanner.setBackgroundColor(Color.parseColor("#1E293B"))
                            root.addView(statusBanner)

                            val grid = GridLayout(this)
                            grid.columnCount = 3
                            grid.setPadding(0, 24, 0, 24)

                            val pads = arrayOf("KICK", "SNARE", "HI-HAT", "CLAP", "808 BASS", "CHORD", "ARP C4", "SYNTH E4", "BELL G4")
                            val padColors = arrayOf("#EF4444", "#F97316", "#FACC15", "#22C55E", "#14B8A6", "#38BDF8", "#6366F1", "#A855F7", "#EC4899")

                            for (i in 0 until pads.size) {
                                val label = pads[i]
                                val padBtn = Button(this)
                                padBtn.text = label
                                padBtn.setTextColor(Color.WHITE)
                                padBtn.setBackgroundColor(Color.parseColor(padColors[i]))
                                val gp = GridLayout.LayoutParams()
                                gp.width = 0
                                gp.height = 170
                                gp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                                gp.setMargins(8, 8, 8, 8)
                                padBtn.layoutParams = gp
                                padBtn.setOnClickListener {
                                    statusBanner.text = "TRIGGERED: ${'$'}label • ${'$'}bpm BPM"
                                }
                                grid.addView(padBtn)
                            }
                            root.addView(grid)

                            val transport = LinearLayout(this)
                            transport.orientation = LinearLayout.HORIZONTAL
                            val playBtn = Button(this)
                            playBtn.text = "Start / Stop"
                            playBtn.setTextColor(Color.WHITE)
                            playBtn.setBackgroundColor(Color.parseColor("#10B981"))
                            playBtn.setOnClickListener {
                                playing = !playing
                                if (playing) {
                                    handler.post(stepLoop)
                                } else {
                                    handler.removeCallbacks(stepLoop)
                                    statusBanner.text = "PAUSED • ${'$'}bpm BPM"
                                }
                            }
                            val tempoUp = Button(this)
                            tempoUp.text = "+10 BPM"
                            tempoUp.setOnClickListener {
                                bpm = Math.min(200, bpm + 10)
                                statusBanner.text = "TEMPO: ${'$'}bpm BPM"
                            }
                            transport.addView(playBtn)
                            transport.addView(tempoUp)
                            root.addView(transport)
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.EXPENSE_TRACKER -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.EditText
                    import android.widget.LinearLayout
                    import android.widget.ScrollView
                    import android.widget.TextView
                    import android.widget.Toast

                    class MainActivity : Activity() {
                        private var balance = 1250.0
                        private lateinit var balanceLabel: TextView
                        private lateinit var descInput: EditText
                        private lateinit var amountInput: EditText
                        private lateinit var ledgerList: LinearLayout

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0F172A"))
                            root.setPadding(28, 36, 28, 28)

                            balanceLabel = TextView(this)
                            balanceLabel.text = "Balance: $1250.00"
                            balanceLabel.textSize = 28f
                            balanceLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            balanceLabel.setTextColor(Color.parseColor("#34D399"))
                            balanceLabel.setPadding(24, 24, 24, 24)
                            balanceLabel.setBackgroundColor(Color.parseColor("#1E293B"))
                            root.addView(balanceLabel)

                            descInput = EditText(this)
                            descInput.hint = "Category or item (e.g. Cloud Hosting)"
                            descInput.setTextColor(Color.WHITE)
                            descInput.setHintTextColor(Color.parseColor("#94A3B8"))
                            root.addView(descInput)

                            amountInput = EditText(this)
                            amountInput.hint = "Amount (e.g. 24.50)"
                            amountInput.setTextColor(Color.WHITE)
                            amountInput.setHintTextColor(Color.parseColor("#94A3B8"))
                            root.addView(amountInput)

                            val row = LinearLayout(this)
                            row.orientation = LinearLayout.HORIZONTAL
                            val expenseBtn = Button(this)
                            expenseBtn.text = "- Expense"
                            expenseBtn.setTextColor(Color.WHITE)
                            expenseBtn.setBackgroundColor(Color.parseColor("#EF4444"))
                            expenseBtn.setOnClickListener { recordEntry(false) }

                            val incomeBtn = Button(this)
                            incomeBtn.text = "+ Income"
                            incomeBtn.setTextColor(Color.WHITE)
                            incomeBtn.setBackgroundColor(Color.parseColor("#10B981"))
                            incomeBtn.setOnClickListener { recordEntry(true) }

                            row.addView(expenseBtn)
                            row.addView(incomeBtn)
                            root.addView(row)

                            val scroll = ScrollView(this)
                            ledgerList = LinearLayout(this)
                            ledgerList.orientation = LinearLayout.VERTICAL
                            scroll.addView(ledgerList)
                            root.addView(scroll)
                            setContentView(root)
                        }

                        private fun recordEntry(isIncome: Boolean) {
                            var label = descInput.text.toString().trim()
                            if (label.isEmpty()) label = if (isIncome) "Deposit" else "Purchase"
                            val rawAmt = amountInput.text.toString().trim()
                            val amt = if (rawAmt.isEmpty()) 10.0 else Math.abs(rawAmt.toDouble())
                            balance += if (isIncome) amt else -amt
                            balanceLabel.text = String.format("Balance: $%.2f", balance)
                            val item = TextView(this)
                            val sign = if (isIncome) "+ $" else "- $"
                            item.text = sign + String.format("%.2f", amt) + "  •  " + label
                            item.setTextColor(if (isIncome) Color.parseColor("#34D399") else Color.parseColor("#FCA5A5"))
                            item.setPadding(16, 14, 16, 14)
                            ledgerList.addView(item, 0)
                            descInput.setText("")
                            amountInput.setText("")
                            Toast.makeText(this@MainActivity, "Entry logged", Toast.LENGTH_SHORT).show()
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.QUIZ_STUDIO -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.LinearLayout
                    import android.widget.TextView
                    import android.widget.Toast

                    class MainActivity : Activity() {
                        private val questions = arrayOf(
                            "Which file declares an Android app's launcher Activity?",
                            "Which bytecode format runs on Android ART?",
                            "Which layout arranges child views vertically or horizontally?",
                            "What signs an APK cleanly with v1/v2/v3 schemes?"
                        )
                        private val answers = arrayOf(
                            "AndroidManifest.xml",
                            "DEX (Dalvik Executable)",
                            "LinearLayout",
                            "ApkSigner"
                        )
                        private val opt0 = arrayOf("AndroidManifest.xml", "build.gradle", "strings.xml", "styles.xml")
                        private val opt1 = arrayOf("DEX (Dalvik Executable)", "ELF64", "WASM", "MSIL")
                        private val opt2 = arrayOf("LinearLayout", "RecyclerView", "SurfaceView", "IntentFilter")
                        private val opt3 = arrayOf("ApkSigner", "ZipAligner", "DexMerge", "Aapt")

                        private var idx = 0
                        private var score = 0
                        private var streak = 0
                        private lateinit var scoreView: TextView
                        private lateinit var questionView: TextView
                        private lateinit var btn0: Button
                        private lateinit var btn1: Button
                        private lateinit var btn2: Button
                        private lateinit var btn3: Button

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0B1120"))
                            root.setPadding(32, 48, 32, 32)

                            scoreView = TextView(this)
                            scoreView.setTextColor(Color.parseColor("#38BDF8"))
                            scoreView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                            scoreView.textSize = 16f
                            root.addView(scoreView)

                            questionView = TextView(this)
                            questionView.setTextColor(Color.WHITE)
                            questionView.textSize = 20f
                            questionView.setTypeface(Typeface.DEFAULT_BOLD)
                            questionView.setPadding(0, 24, 0, 32)
                            root.addView(questionView)

                            btn0 = makeOptionButton(0)
                            root.addView(btn0)
                            btn1 = makeOptionButton(1)
                            root.addView(btn1)
                            btn2 = makeOptionButton(2)
                            root.addView(btn2)
                            btn3 = makeOptionButton(3)
                            root.addView(btn3)

                            renderQuestion()
                            setContentView(root)
                        }

                        private fun makeOptionButton(choice: Int): Button {
                            val b = Button(this)
                            b.setTextColor(Color.WHITE)
                            b.setBackgroundColor(Color.parseColor("#1E293B"))
                            val lp = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            lp.setMargins(0, 10, 0, 10)
                            b.layoutParams = lp
                            b.setOnClickListener { answerQuestion(choice) }
                            return b
                        }

                        private fun currentOption(qIdx: Int, c: Int): String {
                            if (qIdx == 0) return opt0[c]
                            if (qIdx == 1) return opt1[c]
                            if (qIdx == 2) return opt2[c]
                            return opt3[c]
                        }

                        private fun renderQuestion() {
                            val qIdx = idx % questions.size
                            scoreView.text = "SCORE: ${'$'}score   STREAK: x${'$'}streak   Q${'$'}{qIdx + 1}/${'$'}{questions.size}"
                            questionView.text = questions[qIdx]
                            btn0.text = currentOption(qIdx, 0)
                            btn1.text = currentOption(qIdx, 1)
                            btn2.text = currentOption(qIdx, 2)
                            btn3.text = currentOption(qIdx, 3)
                        }

                        private fun answerQuestion(choice: Int) {
                            val qIdx = idx % questions.size
                            val picked = currentOption(qIdx, choice)
                            if (picked.equals(answers[qIdx])) {
                                streak++
                                score += 100 * streak
                                Toast.makeText(this@MainActivity, "Correct! +${'$'}{100 * streak}", Toast.LENGTH_SHORT).show()
                            } else {
                                streak = 0
                                Toast.makeText(this@MainActivity, "Answer: ${'$'}{answers[qIdx]}", Toast.LENGTH_SHORT).show()
                            }
                            idx++
                            renderQuestion()
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.WEATHER_DASHBOARD -> {
                files["src/$pkgPath/MainActivity.kt"] = """
                    package $packageName

                    import android.app.Activity
                    import android.graphics.Color
                    import android.graphics.Typeface
                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.LinearLayout
                    import android.widget.TextView

                    class MainActivity : Activity() {
                        private lateinit var cityTitle: TextView
                        private lateinit var tempView: TextView
                        private lateinit var metaView: TextView
                        private lateinit var chartView: ForecastChartView

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val root = LinearLayout(this)
                            root.orientation = LinearLayout.VERTICAL
                            root.setBackgroundColor(Color.parseColor("#0B1324"))
                            root.setPadding(28, 40, 28, 28)

                            val cityBar = LinearLayout(this)
                            cityBar.orientation = LinearLayout.HORIZONTAL
                            val cities = arrayOf("Tokyo", "London", "New York", "Cairo")
                            val temps = intArrayOf(23, 15, 19, 31)
                            val conds = arrayOf("Clear • Wind 11 km/h", "Light Mist • Wind 18 km/h", "Partly Cloudy • Wind 14 km/h", "Sunny • Wind 9 km/h")

                            cityTitle = TextView(this)
                            cityTitle.setTextColor(Color.parseColor("#93C5FD"))
                            cityTitle.textSize = 18f
                            cityTitle.setPadding(0, 24, 0, 8)

                            tempView = TextView(this)
                            tempView.setTextColor(Color.WHITE)
                            tempView.textSize = 48f
                            tempView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)

                            metaView = TextView(this)
                            metaView.setTextColor(Color.parseColor("#94A3B8"))
                            metaView.setPadding(0, 8, 0, 24)

                            chartView = ForecastChartView(this)

                            for (i in 0 until cities.size) {
                                val idx = i
                                val b = Button(this)
                                b.text = cities[i]
                                b.setTextColor(Color.WHITE)
                                b.setBackgroundColor(Color.parseColor("#1E293B"))
                                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                lp.setMargins(4, 0, 4, 0)
                                b.layoutParams = lp
                                b.setOnClickListener {
                                    cityTitle.text = cities[idx] + " Weather Station"
                                    tempView.text = "${'$'}{temps[idx]}°C"
                                    metaView.text = conds[idx]
                                    chartView.setBaseTemp(temps[idx])
                                }
                                cityBar.addView(b)
                            }

                            root.addView(cityBar)
                            root.addView(cityTitle)
                            root.addView(tempView)
                            root.addView(metaView)
                            val chartLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                            root.addView(chartView, chartLp)

                            cityTitle.text = cities[0] + " Weather Station"
                            tempView.text = "${'$'}{temps[0]}°C"
                            metaView.text = conds[0]
                            chartView.setBaseTemp(temps[0])
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/ForecastChartView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.view.View

                    class ForecastChartView(context: Context) : View(context) {
                        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                        private var baseTemp = 23

                        fun setBaseTemp(t: Int) {
                            baseTemp = t
                            invalidate()
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val w = Math.max(1f, getWidth().toFloat())
                            val h = Math.max(1f, getHeight().toFloat())
                            canvas.drawColor(Color.parseColor("#111827"))

                            val offsets = intArrayOf(-3, -2, 0, 2, 3, 1, -1)
                            val stepX = w / (offsets.size + 1)
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 6f
                            paint.color = Color.parseColor("#38BDF8")

                            var prevX = 0f
                            var prevY = 0f
                            for (i in 0 until offsets.size) {
                                val x = stepX * (i + 1)
                                val y = h * 0.55f - offsets[i] * 26f
                                if (i > 0) canvas.drawLine(prevX, prevY, x, y, paint)
                                prevX = x
                                prevY = y
                            }

                            paint.style = Paint.Style.FILL
                            for (i in 0 until offsets.size) {
                                val x = stepX * (i + 1)
                                val y = h * 0.55f - offsets[i] * 26f
                                paint.color = Color.parseColor("#FBBF24")
                                canvas.drawCircle(x, y, 10f, paint)
                                paint.color = Color.WHITE
                                paint.textSize = 26f
                                paint.textAlign = Paint.Align.CENTER
                                canvas.drawText("${'$'}{baseTemp + offsets[i]}°", x, y - 20f, paint)
                            }
                        }
                    }
                """.trimIndent() + "\n"
            }

            ProjectTemplate.PIXEL_SPRITE_EDITOR -> {
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

                            val canvasView = PixelCanvasView(this)
                            val canvasLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                            root.addView(canvasView, canvasLp)

                            val palette = LinearLayout(this)
                            palette.orientation = LinearLayout.HORIZONTAL
                            palette.setBackgroundColor(Color.parseColor("#182030"))
                            palette.setPadding(12, 12, 12, 12)

                            val swatches = intArrayOf(
                                Color.parseColor("#38BDF8"),
                                Color.parseColor("#22C55E"),
                                Color.parseColor("#FACC15"),
                                Color.parseColor("#F43F5E"),
                                Color.parseColor("#FFFFFF")
                            )
                            val names = arrayOf("Sky", "Lime", "Gold", "Rose", "White")
                            for (i in 0 until swatches.size) {
                                val c = swatches[i]
                                val b = Button(this)
                                b.text = names[i]
                                b.setTextColor(Color.BLACK)
                                b.setBackgroundColor(c)
                                b.setOnClickListener { canvasView.setBrushColor(c) }
                                palette.addView(b)
                            }
                            val clearBtn = Button(this)
                            clearBtn.text = "Clear"
                            clearBtn.setOnClickListener { canvasView.clearGrid() }
                            palette.addView(clearBtn)

                            root.addView(palette)
                            setContentView(root)
                        }
                    }
                """.trimIndent() + "\n"

                files["src/$pkgPath/PixelCanvasView.kt"] = """
                    package $packageName

                    import android.content.Context
                    import android.graphics.Canvas
                    import android.graphics.Color
                    import android.graphics.Paint
                    import android.view.MotionEvent
                    import android.view.View

                    class PixelCanvasView(context: Context) : View(context) {
                        private val gridSize = 12
                        private val pixels = IntArray(gridSize * gridSize)
                        private val paint = Paint()
                        private var activeColor = Color.parseColor("#38BDF8")

                        init {
                            clearGrid()
                        }

                        fun setBrushColor(color: Int) {
                            activeColor = color
                        }

                        fun clearGrid() {
                            for (i in 0 until pixels.size) {
                                pixels[i] = 0
                            }
                            invalidate()
                        }

                        override fun onDraw(canvas: Canvas) {
                            super.onDraw(canvas)
                            val board = Math.min(getWidth(), getHeight()) - 32f
                            val cell = board / gridSize
                            val ox = (getWidth() - board) * 0.5f
                            val oy = (getHeight() - board) * 0.5f
                            canvas.drawColor(Color.parseColor("#0B0F17"))

                            for (r in 0 until gridSize) {
                                for (c in 0 until gridSize) {
                                    val col = pixels[r * gridSize + c]
                                    val l = ox + c * cell
                                    val t = oy + r * cell
                                    paint.style = Paint.Style.FILL
                                    paint.color = if (col == 0) Color.parseColor("#111827") else col
                                    canvas.drawRect(l + 1f, t + 1f, l + cell - 1f, t + cell - 1f, paint)
                                    paint.style = Paint.Style.STROKE
                                    paint.color = Color.parseColor("#1E293B")
                                    canvas.drawRect(l, t, l + cell, t + cell, paint)
                                }
                            }
                        }

                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                                val board = Math.min(getWidth(), getHeight()) - 32f
                                val ox = (getWidth() - board) * 0.5f
                                val oy = (getHeight() - board) * 0.5f
                                val c = (((event.x - ox) / board) * gridSize).toInt()
                                val r = (((event.y - oy) / board) * gridSize).toInt()
                                if (c >= 0 && c < gridSize && r >= 0 && r < gridSize) {
                                    pixels[r * gridSize + c] = activeColor
                                    invalidate()
                                }
                                return true
                            }
                            return super.onTouchEvent(event)
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
