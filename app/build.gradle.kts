import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing settings (see signingConfigs below), read from local.properties
val capriProps: Properties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
val keystore: File? = capriProps.getProperty("capri.keystore")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.capri.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.capri.mobile"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.1-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // her brain: llama.cpp built for phones (arm64) and the emulator (x86_64)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing: the key's location and passwords live in local.properties (never committed). Every release must
    // use the same key, or phones refuse it as an update. Without those lines, release builds use the debug key.
    signingConfigs {
        if (keystore != null) create("capri") {
            storeFile = keystore
            storePassword = capriProps.getProperty("capri.storePassword")
            keyAlias = capriProps.getProperty("capri.keyAlias")
            keyPassword = capriProps.getProperty("capri.keyPassword")
            enableV1Signing = true  // older or stricter installers (some Xiaomi/HyperOS phones) check v1 too
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("capri") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}