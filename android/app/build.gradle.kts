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
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation("androidx.core:core:1.13.1")
}
