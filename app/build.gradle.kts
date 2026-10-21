import com.android.build.api.dsl.ApplicationProductFlavor

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
        fun ApplicationProductFlavor.configureUpdateFeed(propertyName: String, defaultUrl: String) {
            val updateFeedUrl =
                (project.findProperty(propertyName) as String?)
                    ?: defaultUrl
            buildConfigField(
                "String",
                "UPDATE_FEED_URL",
                "\"${updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
            )
        }

        create("phone") {
            dimension = "form"
            isDefault = true
            configureUpdateFeed(
                "updateFeedUrl",
                "https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest.json",
            )
        }
        create("tv") {
            dimension = "form"
            applicationId = "com.mobilerun.portal.tv"
            val captureBox = project.findProperty("tvCaptureBox") as String?
            val dimensions = captureBox?.let {
                Regex("([0-9]+)x([0-9]+)").matchEntire(it)
                    ?: error("tvCaptureBox must be WIDTHxHEIGHT")
            }
            val boxWidth = dimensions?.groupValues?.get(1)?.toInt() ?: 0
            val boxHeight = dimensions?.groupValues?.get(2)?.toInt() ?: 0
            if (dimensions != null) {
                require(boxWidth in 144..1920 && boxHeight in 256..3840 &&
                    boxWidth >= boxHeight && boxWidth % 2 == 0 && boxHeight % 2 == 0) {
                    "tvCaptureBox must be an even landscape box within capture bounds"
                }
            }
            // Zero means no extra bound: the TV encoder's capabilities choose the box.
            buildConfigField("int", "TV_CAPTURE_BOX_WIDTH", boxWidth.toString())
            buildConfigField("int", "TV_CAPTURE_BOX_HEIGHT", boxHeight.toString())
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            configureUpdateFeed(
                "tvUpdateFeedUrl",
                "https://github.com/droidrun/mobilerun-portal/releases/latest/download/latest-tv.json",
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
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
