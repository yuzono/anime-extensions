# Keep Rhino (Mozilla JavaScript engine) classes used by VidGuardExtractor
-keep class org.mozilla.javascript.Context { *; }
-keep class org.mozilla.javascript.NativeJSON { *; }
-keep class org.mozilla.javascript.NativeObject { *; }
-keep class org.mozilla.javascript.Scriptable { *; }
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
