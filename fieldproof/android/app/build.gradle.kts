plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fieldproof"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.fieldproof"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "1.6.1"

        // Server the app talks to (AWS, Sydney). For USB testing against the laptop, set
        // http://127.0.0.1:8080 in the hidden server dialog (long-press the logo).
        buildConfigField("String", "DEFAULT_API_URL", "\"https://54-79-144-31.sslip.io\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
