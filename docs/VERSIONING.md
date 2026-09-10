# Version management

This fork tracks two independent versions.

- `VERSION` is the upstream SilksongAndroid/game compatibility version. It stays aligned with the upstream release being targeted (currently `1.0.3`).
- `APP_REVISION` is this repository's Android/Steam-achievement integration revision. Increment it whenever a new APK revision should be distinguishable from an earlier build of the same upstream version.

The effective APK version is:

`<VERSION>-achievements.<APP_REVISION>`

For example:

`1.0.3-achievements.1`

GitHub Actions also appends the short Git commit SHA to the artifact name so test builds can be traced to their exact source commit.

## Android versionCode

CI derives a monotonically increasing Android `versionCode` as:

`(major * 10000 + minor * 100 + patch) * 100 + APP_REVISION`

For `1.0.3-achievements.1`, the Android versionCode is `1000301`.

`APP_REVISION` must be between 0 and 99. When moving to a new upstream version, reset `APP_REVISION` to 1. The upstream version component makes the resulting Android versionCode increase.

## Release procedure

For a normal achievement-fork update on the same upstream game version:

1. Increment `APP_REVISION`.
2. Add the changes to `CHANGELOG.md` under the new effective version.
3. Build through the `Build APK` workflow.
4. Keep the workflow artifact name and commit SHA with device logs when testing.

When updating to a new upstream SilksongAndroid version, update `VERSION`, reset `APP_REVISION` to `1`, update the changelog, and rebuild.
