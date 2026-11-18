import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.micronizationlab.glafira"
    compileSdk = 35

    val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
        .orNull
        ?.toIntOrNull()
        ?: 1

    defaultConfig {
        applicationId = "com.micronizationlab.glafira"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = "0.1.$ciRunNumber"
    }

    signingConfigs {
        if (providers.environmentVariable("GLAFIRA_DEBUG_KEYSTORE_PATH").isPresent) {
            create("ciDebug") {
                storeFile = file(providers.environmentVariable("GLAFIRA_DEBUG_KEYSTORE_PATH").get())
                storePassword = providers.environmentVariable("GLAFIRA_DEBUG_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("GLAFIRA_DEBUG_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("GLAFIRA_DEBUG_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (providers.environmentVariable("GLAFIRA_DEBUG_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/versions/**"
        }
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
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    // Use a real JVM org.json implementation; android.jar provides non-functional stubs in local unit tests.
    testImplementation("org.json:json:20240303")
}
