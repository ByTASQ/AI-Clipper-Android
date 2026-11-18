pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
        id("com.chaquo.python") version "16.1.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AI-Clipper-Android"
include(":app")
