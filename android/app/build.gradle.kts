import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing lives outside the repo: ~/.config/mote/keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword). Absent file = unsigned release.
val keystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/mote/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.mote.remote"
    compileSdk = 37 // required by Compose 1.12 (BOM 2026.09.00); targetSdk stays 35

    defaultConfig {
        applicationId = "dev.mote.remote"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = File(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
}
