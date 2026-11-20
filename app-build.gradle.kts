plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub gives every build its own number, so each new build counts as an update.
// Building by hand without those numbers falls back to 3 and "1.0".
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 3
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "1.0"

// The signing key. Android only installs an update if it is signed with the SAME key as
// the app already on the phone. The key comes from GitHub secrets (see SIGNING_SETUP.txt).
// Without it, the build still works, but it is signed with a throw-away key.
val keystorePath: String? = System.getenv("NIROBO_KEYSTORE")

android {
    namespace = "org.nirobo.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.nirobo.browser"
        minSdk = 28
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("nirobo") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NIROBO_STORE_PASS")
                keyAlias = System.getenv("NIROBO_KEY_ALIAS") ?: "nirobo"
                keyPassword = System.getenv("NIROBO_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("nirobo") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
