import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin sources itself (built-in Kotlin), so no separate Kotlin plugin is needed.
    alias(libs.plugins.android.application)
}

// Release signing comes from keystore.properties in the project root, which is not in git.
// Without that file, release builds are unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.firetvsettings.pairremote"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.firetvsettings.pairremote"
        // Fire OS 7 = API 28, Fire OS 8 = API 30.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    // Pure-Kotlin ADB client: talks to the TV's adbd over the network (Fix volume tab).
    implementation(libs.dadb)
}
