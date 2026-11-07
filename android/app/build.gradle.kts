import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// کلید امضای ثابت (CI فایل android/key.properties را از GitHub Secrets می‌سازد).
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) keystoreProperties.load(FileInputStream(keystorePropertiesFile))

android {
    namespace = "com.ali.downloader"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.ali.downloader"
        // اندروید ۷ (API 24): yt-dlp امروزی پایتون ۳٫۱۱+ می‌خواهد و youtubedl-android از نسخهٔ ۰٫۱۷ حداقل API 24 است.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // با کلید واقعی اگر موجود باشد، وگرنه کلید debug (برای نصب شخصی کافی است).
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // سخت‌سازی نسخهٔ release (قاعدهٔ امنیتی علی، ۲۰۲۶-۱۱-۰۷): کوچک‌سازی و مبهم‌سازی کد با R8.
            // کد منبع همچنان کامل در مخزن است.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // فایل‌های اجرایی پایتون/FFmpeg/aria2c باید روی دستگاه از APK استخراج شوند.
            useLegacyPackaging = true
            // فقط سه معماری پشتیبانی‌شده؛ x86 حذف می‌شود چون Flutter برای آن موتور ندارد.
            excludes += setOf("lib/x86/**")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val ytdlVersion = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdlVersion")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$ytdlVersion")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}

flutter {
    source = "../.."
}
