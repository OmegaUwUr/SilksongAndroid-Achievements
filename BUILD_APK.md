# One-click APK build

This branch includes a manual GitHub Actions workflow that builds the launcher APK without publishing a release or requiring signing secrets.

1. Push this repository to GitHub.
2. Open **Actions**.
3. Select **Build APK**.
4. Press **Run workflow**.
5. When the run finishes, open it and download the APK from **Artifacts**.

The workflow uses a temporary signing key on every run. If Android says an update has an incompatible signature, uninstall the previous CI-built APK first, then install the new one.

The APK itself does not contain Silksong game data. It keeps the original SilksongAndroid behavior: the app authenticates with Steam, downloads the user's legitimate depot, and performs the on-device port/build.
