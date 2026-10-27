package dev.silksong.shell;

import android.content.Context;
import android.os.Bundle;
import dev.silksong.launcher.HollowKnightBuild;
import dev.silksong.launcher.HollowKnightContext;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Separate process, dex, engine, archives, preferences and saves from Silksong. */
public final class HollowKnightActivity extends GameActivity {
    private RandomAccessFile lockFile;
    private FileLock buildLock;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(HollowKnightContext.wrap(base));
    }

    @Override public File getObbDir() { return new File(getFilesDir(), "pkg"); }
    @Override public File[] getObbDirs() { return new File[] { getObbDir() }; }

    @Override protected void onCreate(Bundle state) {
        try {
            lockFile = new RandomAccessFile(new File(getFilesDir(), "port.lock"), "rw");
            buildLock = lockFile.getChannel().tryLock();
            if (buildLock == null || !HollowKnightBuild.isReady(this))
                throw new IllegalStateException("Hollow Knight is not ready or is being rebuilt");
            super.onCreate(state);
        } catch (Exception failure) {
            android.util.Log.e("HollowKnightPort", "Launch stopped", failure);
            // Unity cannot safely continue after partial initialization.
            releaseLock();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override protected void onDestroy() {
        try { super.onDestroy(); } finally { releaseLock(); }
    }

    private void releaseLock() {
        try { if (buildLock != null) buildLock.release(); } catch (Exception ignored) { }
        try { if (lockFile != null) lockFile.close(); } catch (Exception ignored) { }
        buildLock = null;
        lockFile = null;
    }
}
