# R8 rules for the release build.

# Custom views are created by name from layout XML.
-keep class com.codleander.gamebooster.AuroraBackgroundView { *; }
-keep class com.codleander.gamebooster.BoostRingView { *; }
-keep class com.codleander.gamebooster.HistoryChartView { *; }

# Components that the system instantiates from the manifest.
-keep class com.codleander.gamebooster.MainActivity { *; }
-keep class com.codleander.gamebooster.BoosterService { *; }
-keep class com.codleander.gamebooster.BoosterTileService { *; }
-keep class com.codleander.gamebooster.BoosterWidgetProvider { *; }

# Keep stack traces readable through the generated mapping file only.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Drop every log call from release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
