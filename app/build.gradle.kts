import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.micronizationlab.glafira"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.micronizationlab.glafira"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("glafiraVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("glafiraVersionName").orElse("0.1").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("GLAFIRA_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GLAFIRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GLAFIRA_KEY_ALIAS")
                keyPassword = System.getenv("GLAFIRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            val hasReleaseKey = !System.getenv("GLAFIRA_KEYSTORE_PATH").isNullOrBlank()
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
