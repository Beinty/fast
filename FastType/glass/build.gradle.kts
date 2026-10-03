plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huc.glass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.huc.glass"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // the same key as the keyboard, so both installs trust each other
    signingConfigs {
        create("huc") {
            storeFile = file("../app/huc.jks")
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
}
