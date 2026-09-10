// Root project — declares plugin versions for sub-modules.
// We pin to AGP 8.7+ to match Unity 6's bundled Gradle 8.11.
plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// Stock JavaSteam 1.8.0 can read user stats but cannot store achievement
// bitfields back to Steam. GameNative uses this maintained fork, which adds
// storeUserStats(ClientStoreUserStats2) while keeping the 1.8 API surface.
//
// Only substitute the core library. The launcher's existing depot downloader
// intentionally remains on in.dragonbra:javasteam-depotdownloader:1.8.0: that
// version exposes maxFileWrites, which DepotFetcher uses for its tuned Android
// download pipeline. The GameNative depot-downloader fork removed that option.
// Any transitive dependency from the stock downloader on the core javasteam
// module is still replaced by the rule below, so the process uses one core
// JavaSteam implementation and AchievementService still gets storeUserStats().
subprojects {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("in.dragonbra:javasteam"))
                .using(module("io.github.joshuatam:javasteam:1.8.0.1-26-SNAPSHOT"))
        }
    }
}
