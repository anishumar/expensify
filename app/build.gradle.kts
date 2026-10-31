plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anishumar.kharcha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anishumar.kharcha"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // ML Kit ships native code for every CPU type; phones are ARM, so skip the emulator (x86) builds
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Fixed key so each new CI build installs as an update (keeps your data)
    signingConfigs {
        getByName("debug") {
            storeFile = file("kharcha.keystore")
            storePassword = "kharcha123"
            keyAlias = "kharcha"
            keyPassword = "kharcha123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    // on-device text recognition for screens that expose no text to accessibility (model bundled, works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
