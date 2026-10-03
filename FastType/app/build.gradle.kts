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
        versionCode = 13
        versionName = "4.0"
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
}
