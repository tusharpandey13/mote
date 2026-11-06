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
        versionCode = 20
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Fresh process + cleared data per test. The physical-HID suite is opt-in (see README).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        if (project.hasProperty("moteHid")) {
            testInstrumentationRunnerArguments["annotation"] = "dev.mote.remote.hid.PhysicalHid"
        } else {
            testInstrumentationRunnerArguments["notAnnotation"] = "dev.mote.remote.hid.PhysicalHid"
        }
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    sourceSets {
        // Physical-device HID suite lives apart from the emulator suite.
        getByName("androidTest").java.directories.add("src/androidTest/hid")
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
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.org.json) // the JVM test classpath has only stubs of android.jar's org.json

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    debugImplementation(libs.compose.ui.test.manifest)
}
