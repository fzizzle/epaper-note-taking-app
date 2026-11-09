pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Artifex publishes MuPDF's Android library here, not on Maven Central.
        maven("https://maven.ghostscript.com")
    }
}

rootProject.name = "epaper-notes"
include(":app")
