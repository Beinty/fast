import java.util.Properties

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
        versionCode = 65
        versionName = "10.2"

        // the translation engine ships a native library per processor type, and four
        // copies of it is most of the download. This phone is arm64, so keep that one.
        ndk {
            abiFilters += "arm64-v8a"
        }

        // ===================================================================
        //  مفتاح OpenAI — لا تكتبه هنا
        //
        //  المفتاح يجي من GitHub Secret اسمه OPENAI_KEY.
        //  تحطه من: github.com/Beinty/fast → Settings → Secrets and variables
        //           → Actions → New repository secret
        //           Name: OPENAI_KEY     Secret: sk-...
        //
        //  للبناء المحلي: ضيف سطر OPENAI_KEY=sk-... بملف FastType/local.properties
        //  (هذا الملف مضاف لـ .gitignore وما ينرفع).
        //
        //  لا تحطه بـ gradle.properties — ذاك الملف منرفع للريبو.
        //  إذا انكتب المفتاح بالكود وانرفع، OpenAI يلغيه تلقائياً.
        // ===================================================================
        val localKey = rootProject.file("local.properties").let { f ->
            if (!f.exists()) null else Properties().apply {
                f.inputStream().use { p -> load(p) }
            }.getProperty("OPENAI_KEY")
        }
        val openAiKey = localKey
            ?: System.getenv("OPENAI_KEY")
            ?: ""
        buildConfigField("String", "OPENAI_KEY", "\"$openAiKey\"")
    }

    buildFeatures {
        buildConfig = true
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
