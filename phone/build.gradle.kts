plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mediacontrol.remote.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mediacontrol.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    // play-services-wearable drags in fragment 1.1.0, which predates the
    // ActivityResult APIs MainActivity uses for the BLUETOOTH_CONNECT prompt.
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.play.services)
    implementation(libs.coroutines.android)
}
