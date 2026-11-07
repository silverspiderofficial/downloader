# موتورهای youtubedl-android (پایتون/FFmpeg/aria2c) با بازتاب و نام کلاس کار می‌کنند؛ دست‌نخورده می‌مانند.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
-dontwarn javax.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
# Flutter deferred components (استفاده نمی‌شود)
-dontwarn com.google.android.play.core.**
# حذف لاگ‌های اشکال‌زدایی اندروید در release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# نام مقادیر enum در پایگاه داده و پیام‌های IPC ذخیره می‌شود
-keepclassmembers enum com.ali.downloader.** { *; }
