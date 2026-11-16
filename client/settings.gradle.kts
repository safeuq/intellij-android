rootProject.name = "intellij-android-client"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// compose: connection, session and UI, shared by both apps. desktop: the JVM launcher. android: the Android app.
include("compose", "desktop", "android")
