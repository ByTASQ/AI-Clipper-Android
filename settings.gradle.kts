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
        maven { url = uri("${rootDir}/.runanywhere-maven/repository") }
        mavenCentral()
    }
}
rootProject.name = "AI-Clipper-Android"
include(":app")
