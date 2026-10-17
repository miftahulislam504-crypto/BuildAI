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
        versionCode = 8
        versionName = "0.7.0"

        // Phones only; keeps the APK much smaller (Vosk + JNA ship native libs for every ABI).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
}
