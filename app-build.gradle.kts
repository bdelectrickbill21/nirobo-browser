plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.nirobo.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.nirobo.browser"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-beta"
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
