# Loop Wallpaper

An Android app that turns any video into a seamless looping live wallpaper. Everything runs on the phone;
no video is uploaded anywhere.

## How it works

1. **Choose video**: pick a clip from your gallery (or share a video to the app).
2. The app scans every frame and builds a tiny fingerprint of each one.
3. Pick the part of the video to use, roughly how long the loop should be, and how flexible that length is.
   The app compares frames and picks the start and end points that look most alike, including the motion around
   them. It tells you how big the jump at the seam is compared with a normal frame-to-frame change, and shows
   the two frames side by side.
4. **Create loop**: the app writes a new video. With a crossfade, the last moments blend into the start, so the
   seam can't be seen even when the match isn't perfect.
5. **Set as wallpaper** opens Android's live wallpaper preview. Tap *Set wallpaper* there. Later loops replace it
   automatically. The wallpaper pauses whenever it isn't visible, so it doesn't drain the battery.

You can also **Save to gallery** to keep the loop as a video (in Movies/LoopWallpaper).

## Install on your phone

Requires Android 10 or newer.

1. On your phone, open <https://github.com/DevilBike/loop-wallpaper/releases/latest/download/LoopWallpaper.apk>.
   The APK downloads.
2. Open the downloaded file (from the browser's downloads or a notification).
3. If Android says installing from this source isn't allowed, tap **Settings**, turn on
   **Allow from this source**, and go back.
4. Tap **Install**. If Google Play Protect warns that the app is unknown, tap **More details → Install anyway**
   (it warns about any app that isn't from the Play Store).
5. Open **Loop Wallpaper** from your app drawer.

To update, install the newest APK the same way. If Android says the app conflicts with an existing one, uninstall
the old version first (this only happens until a permanent signing key is set up, see below).

## Building

Every push to `main` builds the APK on GitHub Actions and publishes it as a release.

Optional: to sign every build with the same key (so updates install over the old version), add these repository
secrets: `SIGNING_KEYSTORE_BASE64` (the keystore file, base64-encoded), `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
