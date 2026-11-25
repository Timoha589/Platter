# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
-keep class retrofit2.** { *; }

-keep class **.reflect.TypeToken { *; }
-keep class * extends **.reflect.TypeToken
-keep class com.platter.search.TrackChanges { *; }
-keep class com.platter.search.TrackChanges$Change { *; }

# The optional AutoMix endpoint is decoded reflectively by Gson.
-keepclasseswithmembers,allowobfuscation class com.platter.automix.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclasseswithmembers,allowobfuscation class com.cappielloantonio.tempo.service.AutoMixAnalysis$* {
    @com.google.gson.annotations.SerializedName <fields>;
}
