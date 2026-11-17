package com.example.compiler

/**
 * Generates a small self-contained Java 8 `_KtRt.java` runtime companion inside `build/kotlin-gen`
 * whenever Kotlin sources are compiled on-device via ECJ + Multi-DEX.
 * Gives offline Kotlin projects zero-dependency support for collection builders, pairs,
 * range coercion, random numbers, and string helpers.
 */
internal object KotlinRuntimeHelper {

    fun buildSource(packageName: String): String = """
        package $packageName;

        import java.util.ArrayList;
        import java.util.Arrays;
        import java.util.Collection;
        import java.util.Collections;
        import java.util.HashMap;
        import java.util.LinkedHashMap;
        import java.util.LinkedHashSet;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        import java.util.concurrent.ThreadLocalRandom;

        public final class _KtRt {
            private _KtRt() {}

            public static final class Pair<A, B> {
                public final A first;
                public final B second;

                public Pair(A first, B second) {
                    this.first = first;
                    this.second = second;
                }

                public A getFirst() { return first; }
                public B getSecond() { return second; }

                @Override
                public String toString() {
                    return "(" + first + ", " + second + ")";
                }

                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (!(o instanceof Pair)) return false;
                    Pair<?, ?> other = (Pair<?, ?>) o;
                    return java.util.Objects.equals(first, other.first) &&
                           java.util.Objects.equals(second, other.second);
                }

                @Override
                public int hashCode() {
                    return java.util.Objects.hash(first, second);
                }
            }

            public static <A, B> Pair<A, B> pair(A first, B second) {
                return new Pair<A, B>(first, second);
            }

            @SafeVarargs
            public static <T> List<T> listOf(T... items) {
                if (items == null || items.length == 0) return new ArrayList<T>();
                return new ArrayList<T>(Arrays.asList(items));
            }

            @SafeVarargs
            public static <T> ArrayList<T> mutableListOf(T... items) {
                if (items == null || items.length == 0) return new ArrayList<T>();
                return new ArrayList<T>(Arrays.asList(items));
            }

            @SafeVarargs
            public static <T> Set<T> setOf(T... items) {
                LinkedHashSet<T> set = new LinkedHashSet<T>();
                if (items != null) Collections.addAll(set, items);
                return set;
            }

            @SafeVarargs
            public static <T> LinkedHashSet<T> mutableSetOf(T... items) {
                LinkedHashSet<T> set = new LinkedHashSet<T>();
                if (items != null) Collections.addAll(set, items);
                return set;
            }

            @SafeVarargs
            public static <K, V> Map<K, V> mapOf(Pair<K, V>... entries) {
                LinkedHashMap<K, V> map = new LinkedHashMap<K, V>();
                if (entries != null) {
                    for (Pair<K, V> p : entries) {
                        if (p != null) map.put(p.first, p.second);
                    }
                }
                return map;
            }

            @SafeVarargs
            public static <K, V> HashMap<K, V> mutableMapOf(Pair<K, V>... entries) {
                HashMap<K, V> map = new HashMap<K, V>();
                if (entries != null) {
                    for (Pair<K, V> p : entries) {
                        if (p != null) map.put(p.first, p.second);
                    }
                }
                return map;
            }

            public static int coerceIn(int v, int min, int max) {
                return v < min ? min : (v > max ? max : v);
            }

            public static long coerceIn(long v, long min, long max) {
                return v < min ? min : (v > max ? max : v);
            }

            public static float coerceIn(float v, float min, float max) {
                return v < min ? min : (v > max ? max : v);
            }

            public static double coerceIn(double v, double min, double max) {
                return v < min ? min : (v > max ? max : v);
            }

            public static int coerceAtLeast(int v, int min) { return v < min ? min : v; }
            public static long coerceAtLeast(long v, long min) { return v < min ? min : v; }
            public static float coerceAtLeast(float v, float min) { return v < min ? min : v; }
            public static double coerceAtLeast(double v, double min) { return v < min ? min : v; }

            public static int coerceAtMost(int v, int max) { return v > max ? max : v; }
            public static long coerceAtMost(long v, long max) { return v > max ? max : v; }
            public static float coerceAtMost(float v, float max) { return v > max ? max : v; }
            public static double coerceAtMost(double v, double max) { return v > max ? max : v; }

            public static int randInt() { return ThreadLocalRandom.current().nextInt(); }
            public static int randInt(int until) { return until <= 0 ? 0 : ThreadLocalRandom.current().nextInt(until); }
            public static int randInt(int from, int until) { return until <= from ? from : ThreadLocalRandom.current().nextInt(from, until); }
            public static float randFloat() { return ThreadLocalRandom.current().nextFloat(); }
            public static double randDouble() { return ThreadLocalRandom.current().nextDouble(); }
            public static double randDouble(double until) { return ThreadLocalRandom.current().nextDouble(until); }
            public static boolean randBoolean() { return ThreadLocalRandom.current().nextBoolean(); }

            public static boolean isNotBlank(CharSequence cs) {
                if (cs == null) return false;
                for (int i = 0; i < cs.length(); i++) {
                    if (!Character.isWhitespace(cs.charAt(i))) return true;
                }
                return false;
            }

            public static boolean isNullOrBlank(CharSequence cs) {
                return !isNotBlank(cs);
            }

            public static String orEmpty(String s) {
                return s == null ? "" : s;
            }

            public static String joinToString(Collection<?> c, String sep) {
                if (c == null || c.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object item : c) {
                    if (!first) sb.append(sep);
                    sb.append(item);
                    first = false;
                }
                return sb.toString();
            }
        }
    """.trimIndent() + "\n"
}
