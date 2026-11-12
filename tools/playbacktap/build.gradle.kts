// Development-only helper that hears what the other apps play (their media sound) and hands it to
// CarStage over a local socket, for an emulator without the MediaProjection consent dialog. It is
// not installed: scripts/playback-tap.sh pushes the APK and runs it with app_process as the shell
// user, which holds CAPTURE_AUDIO_OUTPUT and MODIFY_AUDIO_ROUTING (docs/05 "재생 소리 캡처 확인").
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.carstage.playbacktap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.carstage.playbacktap"
        // AudioManager.registerAudioPolicyStatic, which a process without an app context needs, is API 33.
        minSdk = 33
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
        // The helper runs as the shell user through app_process and reaches the system APIs it
        // needs by reflection on purpose: there is no other way without the consent dialog.
        disable += setOf("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
        // The process lives only as long as one capture and has no activities to leak, so holding
        // its one context in a static field is how it reaches the system services.
        disable += "StaticFieldLeak"
    }
}
