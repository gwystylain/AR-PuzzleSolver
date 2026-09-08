# Releasing

A tagged push builds a signed APK and attaches it to a GitHub Release, so the
[latest release][latest] page is always a working download link.

[latest]: https://github.com/gwystylain/AR-PuzzleSolver/releases/latest

## One-time setup: the signing key

An Android APK must be signed to install, and **the signature must stay the same
across versions** — Android refuses to install an update signed by a different
key, so changing it forces every user to uninstall first and lose their data.
That makes this keystore worth backing up: lose it and you cannot ship an update
to anyone who already installed the app.

Generate it once, on your own machine. Pick a real password when prompted; it is
never stored in the repo.

```bash
keytool -genkeypair -v -keystore release.jks -alias puzzlesolver \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then hand it to CI as four repository secrets. The keystore itself goes in
base64-encoded, because a secret has to be text:

```bash
gh secret set SIGNING_KEYSTORE_BASE64 --repo gwystylain/AR-PuzzleSolver < <(base64 -w0 release.jks)
gh secret set SIGNING_STORE_PASSWORD  --repo gwystylain/AR-PuzzleSolver
gh secret set SIGNING_KEY_ALIAS       --repo gwystylain/AR-PuzzleSolver --body puzzlesolver
gh secret set SIGNING_KEY_PASSWORD    --repo gwystylain/AR-PuzzleSolver
```

The two password commands prompt for the value rather than taking it as an
argument, which keeps it out of your shell history.

Now move `release.jks` somewhere safe and out of the working tree. `*.jks` is
gitignored, but the safest copy is the one that is not in the project directory
at all.

## Cutting a release

Set the version in [`app/build.gradle.kts`](../app/build.gradle.kts) and tag it.
`versionName` is what users see; `versionCode` is what Android compares when
deciding whether one APK is newer than another, so it must increase every time.

```bash
git commit -am "Release 0.2.0" && git push
git tag v0.2.0 && git push origin v0.2.0
```

The tag drives everything downstream: the workflow runs the tests, builds
`:app:assembleRelease`, verifies the resulting signature with `apksigner`, and
publishes a release named after the tag with `PuzzleSolver-0.2.0.apk` attached
and auto-generated notes from the commits since the last tag.

If the tests fail, no release is created.

## Checking the pipeline without releasing

"Run workflow" on the [Release APK action][action] builds from a branch without
a tag. It skips signing and the release step, and leaves an unsigned APK as a
workflow artifact — enough to confirm the build works, not enough to install.

[action]: https://github.com/gwystylain/AR-PuzzleSolver/actions/workflows/release.yml

## Building one locally

`./gradlew :app:assembleRelease` with none of the `SIGNING_*` variables set
falls back to the debug key, which produces an APK you can install on your own
device. It is not interchangeable with a released one — different key, so it
will not upgrade an installed release build.

## What users see

The APK is not from Google Play, so Android asks for "install unknown apps"
permission for whatever app opened the file. The device also needs
[Google Play Services for AR][arcore], and the ABI filter means arm64/armv7
only — an x86 emulator cannot run it.

[arcore]: https://play.google.com/store/apps/details?id=com.google.ar.core
