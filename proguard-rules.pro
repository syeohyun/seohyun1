# JavascriptInterface methods must keep their names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
