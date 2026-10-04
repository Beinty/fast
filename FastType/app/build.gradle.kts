plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huc.fasttype"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.huc.fasttype"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "6.5"

        // the translation engine ships a native library per processor type, and four
        // copies of it is most of the download. This phone is arm64, so keep that one.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("huc") {
            storeFile = file("huc.jks")
            storePassword = "hucfasttype"
            keyAlias = "huc"
            keyPassword = "hucfasttype"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("huc")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("huc")
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
    // on-device translation: no API key, and nothing typed ever leaves the phone
    implementation("com.google.mlkit:translate:17.0.3")
}
