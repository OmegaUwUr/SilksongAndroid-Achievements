// Root project — declares plugin versions for sub-modules.
// We pin to AGP 8.7+ to match Unity 6's bundled Gradle 8.11.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// Stock JavaSteam 1.8.0 can read user stats but cannot store achievement
// bitfields back to Steam. GameNative uses this maintained fork, which adds
// storeUserStats(ClientStoreUserStats2) while keeping the 1.8 API surface.
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("in.dragonbra:javasteam"))
                .using(module("io.github.joshuatam:javasteam:1.8.0.1-26-SNAPSHOT"))
            substitute(module("in.dragonbra:javasteam-depotdownloader"))
                .using(module("io.github.joshuatam:javasteam-depotdownloader:1.8.0.1-26-SNAPSHOT"))
        }
    }
}
