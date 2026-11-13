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
        // GeckoView is published to Mozilla's own Maven repo, not Central.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "JAVBrowser"
include(":app")
