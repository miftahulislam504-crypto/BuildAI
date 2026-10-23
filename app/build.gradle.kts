plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.buildx.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.buildx.ai"
        minSdk = 29
        targetSdk = 34
        versionCode = 13
        versionName = "0.12.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phones only; keeps the APK much smaller (Vosk + JNA ship native libs for every ABI).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing: the key never lives in the repo. CI (or your own shell) provides it through these variables:
    //   BUILDX_KEYSTORE_FILE (path to the .jks), BUILDX_KEYSTORE_PASSWORD, BUILDX_KEY_ALIAS, BUILDX_KEY_PASSWORD
    // Without them `assembleRelease` still builds, but the APK is unsigned and cannot be installed.
    val keystorePath = System.getenv("BUILDX_KEYSTORE_FILE")
    val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists() &&
        !System.getenv("BUILDX_KEYSTORE_PASSWORD").isNullOrBlank() && !System.getenv("BUILDX_KEY_ALIAS").isNullOrBlank()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("BUILDX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUILDX_KEY_ALIAS")
                keyPassword = System.getenv("BUILDX_KEY_PASSWORD") ?: System.getenv("BUILDX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Not minified on purpose: Vosk and JNA load native code by name, and shrinking breaks that.
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Offline wake word via a grammar-restricted recognizer. No account, no AccessKey.
    // 0.3.47 is the version I could confirm on Maven Central; newer ones exist (e.g. 0.3.75).
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // Unit tests for the command parser (pure Kotlin, run on the JVM; no phone needed).
    testImplementation("junit:junit:4.13.2")
    // org.json is part of Android at runtime, but the stub used by JVM unit tests throws "not mocked".
    testImplementation("org.json:json:20231013")

    // Checks that need a real Android (emulator or phone): gradle connectedDebugAndroidTest
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
