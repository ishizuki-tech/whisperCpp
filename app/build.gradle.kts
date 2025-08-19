/*
  app/build.gradle.kts
  --------------------
  Cleaned and commented Kotlin DSL version of your app module Gradle file.

  - Uses Kotlin DSL (no Groovy syntax).
  - Adds optional key.properties support for release signing.
  - Provides safe, well-logged setup tasks:
      * checkSubmodule  -> init native submodule if missing/empty
      * downloadModel   -> run ./download_models.sh if present (ensures exec permission)
  - Enforces Kotlin JVM target for compile tasks.
  - Keep your libs/version catalog (libs) and plugin aliases as-is.
*/

import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

//////////////////////////////////////////////////////
// Optional signing: load keystore properties if present
//////////////////////////////////////////////////////
//
// If you want to sign release builds with a real keystore, create a
// `key.properties` file at the project root with the following keys:
//   storeFile=/absolute/or/relative/path/to/keystore.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
//
// This block reads that file safely (no file -> fallback to debug signing).
val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

//////////////////////////////////////////////////////
// Setup tasks: ensure native submodule + model script
//////////////////////////////////////////////////////

// checkSubmodule:
// - If `nativelib/whisper_core` does not exist or is empty, run
//   `git submodule update --init --recursive` to initialize it.
// - Uses project.exec so it runs with the project's working directory.
tasks.register("checkSubmodule") {
    description = "Initialize native submodules recursively if missing/empty."
    doLast {
        val submoduleDir = file("nativelib/whisper_core")
        val isEmpty = !(submoduleDir.exists() && (submoduleDir.listFiles()?.isNotEmpty() == true))

        if (isEmpty) {
            logger.lifecycle("🔄 Submodule appears missing or empty — initializing (git submodule update --init --recursive)...")
            // Run git submodule update from the project root
            project.exec {
                commandLine("git", "submodule", "update", "--init", "--recursive")
            }
        } else {
            logger.lifecycle("✅ Submodule already initialized.")
        }
    }
}

// downloadModel:
// - If `download_models.sh` is present, mark it executable and run it.
// - If not present, task is skipped (onlyIf).
tasks.register<Exec>("downloadModel") {
    description = "Execute model download script (download_models.sh) if it exists."
    group = "setup"

    // Skip the task when the script is absent — avoids failure in CI if you don't include the script.
    onlyIf {
        val script = file("download_models.sh")
        if (!script.exists()) {
            logger.warn("⚠️  download_models.sh not found; skipping model download.")
            false
        } else {
            true
        }
    }

    // Ensure the script has execute permission immediately before running.
    doFirst {
        val script = file("download_models.sh")
        if (!script.canExecute()) {
            logger.lifecycle("🔧 Giving execute permission to download_models.sh")
            script.setExecutable(true)
        }
    }

    // Exec task runs the script using bash; change if you want a different shell.
    commandLine("bash", "./download_models.sh")
}

// Make sure preBuild depends on our setup tasks so they run automatically before building.
tasks.named("preBuild") {
    dependsOn("checkSubmodule", "downloadModel")
}

//////////////////////////////////////////////////////
// Android configuration
//////////////////////////////////////////////////////
android {
    // Replace with your real namespace if different
    namespace = "com.negi.stt"

    // Target/compile SDK — update if you test against a newer SDK.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.negi.stt"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing configuration:
    // - If key.properties exists, configure release signing from it.
    // - Otherwise, release falls back to the debug signing key (not for production).
    signingConfigs {
        create("release") {
            // If a storeFile path is provided in key.properties, use it.
            val storeFilePath = keystoreProperties.getProperty("storeFile")?.takeIf { it.isNotBlank() }
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
            } else {
                // leave storeFile null -> indicates no custom keystore configured
                storeFile = null
            }

            keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
            keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
            storePassword = keystoreProperties.getProperty("storePassword") ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            // Keep debug builds fast and debuggable
            isMinifyEnabled = false
        }

        getByName("release") {
            // Enable minification for release builds by default (keep rules in proguard-rules.pro)
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // If no custom keystore is configured, Android Gradle Plugin will use the debug keystore.
            // For production releases, make sure key.properties is present with correct paths/passwords.
            signingConfig = if (keystorePropertiesFile.exists() && (keystoreProperties.getProperty("storeFile")?.isNotBlank() == true)) {
                signingConfigs.getByName("release")
            } else {
                // WARN: signing with debug signing key is unusual for release. Use a proper keystore for production.
                signingConfigs.getByName("debug")
            }
        }
    }

    // Java compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin compile options exposed in Android block (AGP-compatible).
    // Also enforced below on all KotlinCompile tasks to be safe.
    kotlinOptions {
        jvmTarget = "17"
    }

    // Enable Jetpack Compose
    buildFeatures {
        compose = true
    }

    // (Optional) Additional AGP configurations (compose options, packaging options) can go here.
}

//////////////////////////////////////////////////////
// Enforce Kotlin compile jvm target for all Kotlin compile tasks
//////////////////////////////////////////////////////
tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

//////////////////////////////////////////////////////
// Dependencies
//////////////////////////////////////////////////////
dependencies {
    // Your native library module (assumes :nativelib exists in settings.gradle.kts)
    implementation(project(":nativelib"))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Activity & Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Material & Icons
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
