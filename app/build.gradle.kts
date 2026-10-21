plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.mobilerun.portal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mobilerun.portal"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as String).toInt()
        versionName = project.findProperty("versionName") as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "$applicationId-$versionName")
    }

    flavorDimensions += "form"
    productFlavors {
        create("phone") {
            dimension = "form"
            isDefault = true
            val updateFeedUrl =
                (project.findProperty("updateFeedUrl") as String?)
                    ?: "https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest.json"
            buildConfigField(
                "String",
                "UPDATE_FEED_URL",
                "\"${updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
            )
        }
        create("tv") {
            dimension = "form"
            applicationId = "com.mobilerun.portal.tv"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            val updateFeedUrl =
                (project.findProperty("tvUpdateFeedUrl") as String?)
                    ?: "https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest-tv.json"
            buildConfigField(
                "String",
                "UPDATE_FEED_URL",
                "\"${updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("DROIDRUN_KEYSTORE_PATH") ?: "/dev/null")
            storePassword = System.getenv("DROIDRUN_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("DROIDRUN_KEYSTORE_KEY_ALIAS")
            keyPassword = System.getenv("DROIDRUN_KEYSTORE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.java.websocket)
    implementation(libs.webrtc)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
