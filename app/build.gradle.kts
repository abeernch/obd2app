plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abeernch.civicobd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abeernch.civicobd"
        minSdk = 26
        // Kept at 32 on purpose: apps targeting 33+ may not switch Bluetooth on by themselves.
        targetSdk = 32
        // The build pipeline passes the run number, so every APK has a higher version.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("BUILD_NUMBER") ?: "1")
        buildConfigField("boolean", "TEST_HOOKS", "false")
    }

    // One fixed key, so a new APK installs over the old one without uninstalling.
    // If the build pipeline provides a key from its encrypted secrets, that one is used.
    // Otherwise the development key in the repository is used.
    signingConfigs {
        create("personal") {
            val secretKey = System.getenv("SIGNING_KEY_FILE")
            if (secretKey != null && file(secretKey).exists()) {
                storeFile = file(secretKey)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_ALIAS") ?: "civicobd"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/civic-obd.keystore")
                storePassword = "civicobd"
                keyAlias = "civicobd"
                keyPassword = "civicobd"
            }
        }
    }

    buildTypes {
        // The build installed on the phone. Test shortcuts are compiled out.
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
        }
        // Emulator test build only: same app, plus the "demo" and "import_path" launch shortcuts.
        create("uitest") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("personal")
            buildConfigField("boolean", "TEST_HOOKS", "true")
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.glance:glance-appwidget:1.1.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
}
