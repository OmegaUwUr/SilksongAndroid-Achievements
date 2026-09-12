// SilksongApp — custom Application class for the Silksong APK.
//
// Runs ONCE PER PROCESS at process startup, before any Activity is created.
// That is the only place either of the things below can go: both have to be in
// effect before anything else in the process runs.

package dev.silksong.launcher

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

class SilksongApp : Application() {

    private companion object {
        private const val GAME_ACTIVITY = "dev.silksong.shell.GameActivity"
    }

    // Before any class in this app is loaded, which rules out onCreate.
    //
    // The APK links against com.unity3d.player.* and ships none of it: the
    // player classes are dexed on the device out of the module the app
    // downloads. GameActivity's superclass is one of the types that resolves
    // from there, so the dex has to be in the class loader before the
    // framework instantiates the activity. attachBaseContext is the first
    // point in the process where that is possible.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        UnityDex.inject(this)
    }

    override fun onCreate() {
        super.onCreate()

        // First, so that everything below is recorded too. The log is written
        // to a file from here on, which is what makes it survivable enough to
        // be worth asking a user for.
        LauncherLog.attach(this)

        // Before anything else, and in every process: JavaSteam's crypto
        // registers itself the first time it is touched, and on Android it
        // registers the wrong thing unless this has run first. See SteamCrypto.
        SteamCrypto.install()

        // The launcher and Unity game deliberately live in different Android
        // processes, but this Application class is created in both. Watch the
        // real GameActivity lifecycle rather than assuming that pressing Play
        // means the game is still running. Steam presence/playtime therefore
        // starts only while GameActivity is actually visible and is cleared
        // when it stops (background, return to launcher, or normal exit).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity.javaClass.name != GAME_ACTIVITY) return
                LauncherLog.log("Steam presence: GameActivity started")
                AchievementService.reportGameActivity(applicationContext, true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity.javaClass.name != GAME_ACTIVITY) return
                LauncherLog.log("Steam presence: GameActivity stopped")
                AchievementService.reportGameActivity(applicationContext, false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
