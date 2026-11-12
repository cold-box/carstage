plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Whether the release build drives the cabin: lights, climate and seats (docs/11 9장, decision 17).
// Pleos App Market's review fails an app that declares a vehicle permission not agreed with it, or
// calls that permission's API, and CarStage has no agreement for CONTROL_CAR_*. So the release build
// takes src/noCabinControl instead of src/cabinControl: it declares none of those permissions and
// carries no code that calls them (no VehicleCabin), and its stage shows the cabin on the screen
// only, as a preview. Debug builds always drive it, for the emulator's tests.
// Once Pleos agrees, set this to true and build again.
val releaseDrivesCabin = false

// The team's release key: ~/.android/carstage-release.jks, kept with its password in 1Password
// ("CarStage release signing key"), never in this repository. scripts/release-apk.sh puts the
// password in CARSTAGE_RELEASE_STORE_PASSWORD for one build only, so the password itself is never
// written to disk (Gradle's execution history keeps only a hash of it). Without that variable the
// release APK stays unsigned (app-release-unsigned.apk), which CLAUDE.md's local release check
// signs with the debug key.
val releaseStorePassword: String? = providers.environmentVariable("CARSTAGE_RELEASE_STORE_PASSWORD").orNull
val releaseStoreFile: File? = releaseStorePassword?.let {
    val path = providers.environmentVariable("CARSTAGE_RELEASE_STORE_FILE").orNull
        ?: "${System.getProperty("user.home")}/.android/carstage-release.jks"
    file(path).also { store ->
        if (!store.isFile) throw GradleException("CARSTAGE_RELEASE_STORE_PASSWORD is set but the release keystore is not at $store")
    }
}

android {
    namespace = "com.carstage.app"
    compileSdk = 35
    // CarUxRestrictionsManager for driving restrictions; on Android Automotive it is part of the platform.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.carstage.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                // PKCS12 keeps one password for the store and the key.
                keyAlias = providers.environmentVariable("CARSTAGE_RELEASE_KEY_ALIAS").orNull ?: "carstage"
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("debug") {
            java.srcDir("src/cabinControl/java")
        }
        getByName("release") {
            if (releaseDrivesCabin) {
                java.srcDir("src/cabinControl/java")
            } else {
                java.srcDir("src/noCabinControl/java")
                manifest.srcFile("src/noCabinControl/AndroidManifest.xml")
            }
        }
    }
    lint {
        // Any lint warning fails scripts/check.sh: fix it, or disable the check here with the reason.
        warningsAsErrors = true
        // The toolchain is pinned on purpose (toolchain/, gradle/libs.versions.toml) and upgraded
        // deliberately, so "a newer version is available" is not a problem in this change.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.ui.tooling)
}
