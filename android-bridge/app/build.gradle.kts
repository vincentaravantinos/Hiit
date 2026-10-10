plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.vincentaravantinos.hiitbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vincentaravantinos.hiitbridge"
        minSdk = 28
        targetSdk = 36
        // Incrémenté à chaque build CI pour que l'APK s'installe par-dessus l'ancien.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("bridge") {
            // Clé dédiée à cette app perso installée hors Play Store : la même clé à
            // chaque build est ce qui permet d'installer les mises à jour par-dessus.
            storeFile = file("../signing/bridge.jks")
            storePassword = "hiitbridge"
            keyAlias = "bridge"
            keyPassword = "hiitbridge"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bridge")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // SDK Spotify App Remote (absent de Maven : fichier embarqué depuis github.com/spotify/android-sdk).
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
}
