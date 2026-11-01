# HomeWx R8 rules (release builds are minified + obfuscated).
#
# HomeWx's own code needs very little: it parses JSON with the framework's org.json (no
# reflection), uses no Gson models, no @JavascriptInterface and no Class.forName. Libraries
# bring their own consumer rules (wxdata, OkHttp, Firebase, Picasso, MPAndroidChart).

# Readable crash stack traces in Crashlytics / Play Console once mapping.txt is uploaded.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# osmdroid is a pure-Java library with no consumer rules; it is only warned about, not reflected on.
-dontwarn org.osmdroid.**

# Optional OkHttp platform providers that aren't on the classpath.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
