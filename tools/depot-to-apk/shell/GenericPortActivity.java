package dev.silksong.shell;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import dev.silksong.launcher.GenericPortBuild;
import dev.silksong.launcher.GenericPortContext;
import dev.silksong.launcher.BundledNativeLibraries;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** One engine per process lifetime; every game's data and libraries remain in its own namespace. */
public final class GenericPortActivity extends GameActivity {
    private GenericPortContext port;
    private RandomAccessFile runtimeFile, buildFile;
    private FileLock runtimeLock, buildLock;

    @Override protected void attachBaseContext(Context base) {
        port = GenericPortContext.active(base);
        super.attachBaseContext(port);
    }
    @Override public File getObbDir() { return new File(getFilesDir(), "pkg"); }
    @Override public File[] getObbDirs() { return new File[] { getObbDir() }; }

    @Override protected void onCreate(Bundle state) {
        try {
            runtimeFile = new RandomAccessFile(new File(port.getSharedFilesDir(), "generic-runtime.lock"), "rw");
            runtimeLock = runtimeFile.getChannel().tryLock();
            buildFile = new RandomAccessFile(new File(getFilesDir(), "port.lock"), "rw");
            buildLock = buildFile.getChannel().tryLock();
            if (runtimeLock == null || buildLock == null || getIntent().getIntExtra("app_id", 0) != port.getAppId() ||
                GenericPortContext.active(getBaseContextForSelection()).getAppId() != port.getAppId() || !GenericPortBuild.isReady(port))
                throw new IllegalStateException("The selected port is not ready, changed, or is being rebuilt");
            BundledNativeLibraries.preloadForGame(port, new File(getFilesDir(), "pkg/lib/arm64"));
            super.onCreate(state);
        } catch (Exception error) {
            android.util.Log.e("GenericPort", "Launch stopped before Unity initialization", error);
            release();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
    private Context getBaseContextForSelection() { return port.getBaseContext(); }
    @Override protected void onNewIntent(Intent intent) {
        if (intent.getIntExtra("app_id", 0) == port.getAppId()) super.onNewIntent(intent);
    }
    @Override public void onUnityPlayerUnloaded() { finish(); }
    @Override protected void onDestroy() {
        try { super.onDestroy(); } finally {
            release();
            // Engine dex, native globals and plug-ins cannot be safely swapped for a different game.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
    private void release() {
        try { if (buildLock != null) buildLock.release(); } catch (Exception ignored) { }
        try { if (runtimeLock != null) runtimeLock.release(); } catch (Exception ignored) { }
        try { if (buildFile != null) buildFile.close(); } catch (Exception ignored) { }
        try { if (runtimeFile != null) runtimeFile.close(); } catch (Exception ignored) { }
    }
}
