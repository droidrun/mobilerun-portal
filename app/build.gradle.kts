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
            val captureBox = (project.findProperty("tvCaptureBox") as String?) ?: "1280x720"
            val dimensions = Regex("([0-9]+)x([0-9]+)").matchEntire(captureBox)
                ?: error("tvCaptureBox must be WIDTHxHEIGHT")
            val (boxWidth, boxHeight) = dimensions.destructured
            require(boxWidth.toInt() in 144..1920 && boxHeight.toInt() in 256..3840 &&
                boxWidth.toInt() >= boxHeight.toInt() &&
                boxWidth.toInt() % 2 == 0 && boxHeight.toInt() % 2 == 0) {
                "tvCaptureBox must be an even landscape box within capture bounds"
            }
            buildConfigField("int", "TV_CAPTURE_BOX_WIDTH", boxWidth.toInt().toString())
            buildConfigField("int", "TV_CAPTURE_BOX_HEIGHT", boxHeight.toInt().toString())
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
