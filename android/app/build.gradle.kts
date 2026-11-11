plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.personal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.personal"
        minSdk = 26
        // 34 on purpose, 35 forces edge to edge and my plain layout would sit under the status bar
        targetSdk = 34
        versionCode = 6
        versionName = "1.6"
    }

    buildTypes {
        release {
            // not debuggable, but signed with the debug key because this APK is only for my own phone
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Vosk loads its native library from a real file, so the libraries are unpacked at install
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    lint {
        // lint is not what I need from a personal build, a lint failure should not block my APK
        checkReleaseBuilds = false
        abortOnError = false
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
    // offline wake word. Vosk is open source and runs fully on the phone, JNA is what it needs to load.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
