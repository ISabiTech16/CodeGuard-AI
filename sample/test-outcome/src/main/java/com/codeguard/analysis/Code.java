package com.codeguard.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, dependency-free helpers for lightweight source analysis.
 *
 * <p>The central idea is {@link #mask}: it returns a string of the SAME LENGTH as the source where the
 * contents of comments and string/char literals are blanked out. Structural scans (brace matching,
 * "is there an assert call") run on the masked text, so a brace inside a string or an
 * {@code assertTrue} inside a comment can never fool them, and every offset still maps 1:1 onto the
 * original text.</p>
 */
public final class Code {
    private Code() {}

    public static String mask(String src, Lang lang) {
        if (src == null || src.isEmpty()) return "";
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        boolean py = lang == Lang.PYTHON;
        boolean backtickIsString = lang.isJsFamily() || lang == Lang.GO;
        while (i < n) {
            char c = out[i];
            char next = i + 1 < n ? out[i + 1] : '\0';
            if (!py && c == '/' && next == '/') {
                while (i < n && out[i] != '\n') { blank(out, i); i++; }
                continue;
            }
            if (!py && c == '/' && next == '*') {
                blank(out, i); blank(out, i + 1); i += 2;
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) { blank(out, i); i++; }
                if (i < n) { blank(out, i); i++; }
                if (i < n) { blank(out, i); i++; }
                continue;
            }
            if (py && c == '#') {
                while (i < n && out[i] != '\n') { blank(out, i); i++; }
                continue;
            }
            // triple-quoted strings: Java/Kotlin text blocks, Python docstrings
            if ((c == '"' || (py && c == '\'')) && next == c && i + 2 < n && out[i + 2] == c) {
                char q = c;
                i += 3;
                while (i < n && !(out[i] == q && i + 2 < n && out[i + 1] == q && out[i + 2] == q)) {
                    if (out[i] == '\\' && i + 1 < n) { blank(out, i); i++; }
                    blank(out, i); i++;
                }
                i = Math.min(n, i + 3);
                continue;
            }
            if (c == '"' || c == '\'' || (backtickIsString && c == '`')) {
                char q = c;
                i++;
                while (i < n && out[i] != q) {
                    if (out[i] == '\\' && i + 1 < n) { blank(out, i); i++; }
                    if (out[i] == '\n' && q != '`') break; // unterminated literal: stop at end of line
                    blank(out, i); i++;
                }
                if (i < n && out[i] == q) i++;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    private static void blank(char[] a, int i) {
        char c = a[i];
        if (c != '\n' && c != '\r') a[i] = ' ';
    }

    /** Index of the brace that closes the one at {@code open}, or -1. Operates on MASKED text. */
    public static int matchClose(String masked, int open, char openCh, char closeCh) {
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == openCh) depth++;
            else if (c == closeCh) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static int matchBrace(String masked, int open) { return matchClose(masked, open, '{', '}'); }
    public static int matchParen(String masked, int open) { return matchClose(masked, open, '(', ')'); }

    /** Precomputed line starts for O(log n) offset -> 1-based line lookups. */
    public static final class Lines {
        private final int[] starts;
        public Lines(String s) {
            List<Integer> l = new ArrayList<>();
            l.add(0);
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') l.add(i + 1);
            starts = l.stream().mapToInt(Integer::intValue).toArray();
        }
        public int lineAt(int offset) {
            int lo = 0, hi = starts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (starts[mid] <= offset) lo = mid; else hi = mid - 1;
            }
            return lo + 1;
        }
        public int count() { return starts.length; }
    }

    /** Splits an argument list on top-level commas. Operates on MASKED text; returns [start,end) offsets. */
    public static List<int[]> splitArgs(String masked, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0, start = from;
        for (int i = from; i < to; i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { out.add(new int[]{start, i}); start = i + 1; }
        }
        if (to > start || !out.isEmpty()) out.add(new int[]{start, to});
        return out;
    }

    public static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    public static boolean isTestFile(String path) {
        if (path == null) return false;
        String p = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String name = p.substring(p.lastIndexOf('/') + 1);
        if (p.contains("/src/test/") || p.startsWith("src/test/") || p.contains("/__tests__/")) return true;
        if (p.contains("/tests/") || p.startsWith("tests/") || p.contains("/test/") || p.startsWith("test/")) {
            return Lang.of(p).known();
        }
        if (name.endsWith("test.java") || name.endsWith("tests.java") || path.endsWith("IT.java") || path.endsWith("ITCase.java")
                || name.endsWith("test.kt") || name.endsWith("tests.kt") || name.endsWith("spec.kt")
                || name.endsWith("tests.cs") || name.endsWith("test.cs")) return true;
        if (name.matches(".*\\.(test|spec)\\.(js|jsx|ts|tsx|mjs|cjs)$")) return true;
        if (name.endsWith("_test.go") || name.endsWith("_test.py") || name.startsWith("test_") && name.endsWith(".py")) return true;
        return false;
    }
}
