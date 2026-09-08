plugins {
    id("com.android.application")
}

android {
    namespace = "onl.nl0.chengshu"
    compileSdk = 34
    defaultConfig {
        applicationId = "onl.nl0.chengshu"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.6"
    }
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
