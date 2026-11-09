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
        // Boox only serves its pen SDK over plain HTTP (HTTPS returns no metadata).
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}

rootProject.name = "epaper-notes"
include(":app")
