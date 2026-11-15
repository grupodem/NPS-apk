# ProGuard / R8 Rules for Kiosk Launcher

# Keep JavaScript Interface methods called from WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Kiosk components
-keep class com.osamaalek.kiosklauncher.services.** { *; }
-keep class com.osamaalek.kiosklauncher.MyDeviceAdminReceiver { *; }
-keep class com.osamaalek.kiosklauncher.util.KioskUtil { *; }

# Preserve line numbers for stacktraces
-keepattributes SourceFile,LineNumberTable
