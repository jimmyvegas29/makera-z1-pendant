plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "com.z1pendant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.z1pendant"
        minSdk = 31            // VibrationEffect composition primitives (TICK / CLICK / THUD)
        targetSdk = 35
        // Both from gradle.properties (shared with BuildInfo.VERSION in :shared).
        versionCode = providers.gradleProperty("pendantVersionCode").get().toInt()
        versionName = providers.gradleProperty("pendantVersionName").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1") }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // All the app's code and UI live in :shared (Kotlin Multiplatform); this module is the Android shell.
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
}
