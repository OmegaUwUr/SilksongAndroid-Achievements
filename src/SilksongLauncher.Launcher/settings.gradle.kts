pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GameNative's JavaSteam fork publishes achievement-write support here.
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
    }
}

rootProject.name = "silksong-launcher"
include(":app")
