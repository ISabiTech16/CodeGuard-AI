package com.codeguard.analysis;

import java.util.Locale;

/** Languages CodeGuard's deterministic analyzers understand. Anything else is OTHER (no structural analysis). */
public enum Lang {
    JAVA, KOTLIN, CSHARP, JS, TS, PYTHON, GO, OTHER;

    public static Lang of(String path) {
        if (path == null) return OTHER;
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".java")) return JAVA;
        if (p.endsWith(".kt") || p.endsWith(".kts")) return KOTLIN;
        if (p.endsWith(".cs")) return CSHARP;
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) return JS;
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return TS;
        if (p.endsWith(".py")) return PYTHON;
        if (p.endsWith(".go")) return GO;
        return OTHER;
    }

    public boolean isJsFamily() { return this == JS || this == TS; }
    public boolean isJavaFamily() { return this == JAVA || this == KOTLIN || this == CSHARP; }
    public boolean isJvm() { return this == JAVA || this == KOTLIN; }
    public boolean known() { return this != OTHER; }
}
