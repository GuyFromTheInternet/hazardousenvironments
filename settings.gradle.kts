import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AbandonSearchAndroid"
include(":app")

includeBuild("vendor/maplibre-native/platform/android") {
    dependencySubstitution {
        substitute(module("org.maplibre.gl:android-sdk")).using(project(":MapLibreAndroid"))
    }
}
