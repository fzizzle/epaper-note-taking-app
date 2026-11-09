plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fzizzle.epapernotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fzizzle.epapernotes"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The tablet is arm64. Leaving out MuPDF's other three CPU builds keeps the app small.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Onyx's SDK pulls in jars with clashing licence files and a shared C++ runtime;
    // their own demo app needs these same exclusions.
    packaging {
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/ASL2.0")
        jniLibs.pickFirsts += "lib/*/libc++_shared.so"
    }
}

dependencies {
    implementation("com.artifex.mupdf:fitz:1.28.5")
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.9")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
