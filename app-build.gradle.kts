plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.nirobo.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.nirobo.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-beta"
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
