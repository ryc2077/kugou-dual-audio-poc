plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.btsplit.poc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btsplit.poc"
        minSdk = 31 // Android 12：Combined Audio Routing 从这里开始
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.2")
}
