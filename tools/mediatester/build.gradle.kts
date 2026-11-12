// Development-only stand-in for a music app (such as Melon): plays a synthetic song as media
// from a separate package so CarStage can be tested against another app's playback.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.carstage.mediatester"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carstage.mediatester"
        // Typed foreground services need API 29; the Pleos emulator is API 34.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Any lint warning fails scripts/check.sh: fix it, or disable the check here with the reason.
        warningsAsErrors = true
        // The toolchain is pinned on purpose (toolchain/, gradle/libs.versions.toml) and upgraded
        // deliberately, so "a newer version is available" is not a problem in this change.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}
