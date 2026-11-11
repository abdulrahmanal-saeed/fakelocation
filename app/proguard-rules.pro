# R8 rules for the release build. The libraries (AndroidX, Material, Play services, CameraX, Car App) bring their
# own; these cover what they don't.

# osmdroid looks up tile sources and providers by name in places: keep it whole (it's small next to the maps).
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
