plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.vincentaravantinos.maxxusprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.vincentaravantinos.maxxusprobe"
        minSdk = 28
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    signingConfigs {
        create("probe") {
            storeFile = file("../signing/bridge.jks")
            storePassword = "hiitbridge"
            keyAlias = "bridge"
            keyPassword = "hiitbridge"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("probe")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
