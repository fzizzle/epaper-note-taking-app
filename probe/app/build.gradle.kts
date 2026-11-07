plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fzizzle.epapernotes.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fzizzle.epapernotes.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.9")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
