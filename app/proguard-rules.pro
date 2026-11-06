# ===== TrustMe ProGuard Rules =====

# Keep the entire module package — all classes run in hooked app processes
# via Xposed cross-process class loading, which R8 cannot trace.
-keep class hk.kirk.trustme.** { *; }

# libxposed modern API rules (from official README)
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Suppress legacy references (legacy API no longer used)
-dontwarn de.robv.android.xposed.**

# Disable code optimization — R8 optimizes away calls to compileOnly classes
# (XposedInterface.log etc.) since it thinks they have no side effects at runtime
-dontoptimize
