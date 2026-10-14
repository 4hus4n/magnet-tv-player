# Magnet Player (Android TV)

Streams a video from a magnet link: the largest video file in the torrent is downloaded
sequentially and played with ExoPlayer while it downloads. Files are deleted when you exit the player.

## Build
1. Open this folder in Android Studio (Koala or newer, JDK 17).
2. Let Gradle sync, then **Build → Build APK(s)**.
3. APK: `app/build/outputs/apk/debug/app-debug.apk` (or `release`, already signed with the debug key).

Command line (after Android Studio has created the wrapper jar, or with Gradle 8.7 installed):
`gradle assembleRelease`

## Install on the TV
- Enable Developer options → USB/Network debugging on the TV, then
  `adb connect <tv-ip>` and `adb install app-release.apk`, **or**
- Copy the APK to a USB stick / use "Send Files to TV" and install with a file manager.

## Use
- Type/paste the magnet link and press **Play**.
- Easier: on the main screen the TV shows `http://<tv-ip>:8080` — open it on your phone
  (same Wi-Fi), paste the link, tap **Play on TV**.
- Other apps can open `magnet:` links directly in this player.
- A bare 40-character info-hash also works.

## Notes
- Remote: OK = play/pause, Left/Right = seek. Seeking ahead waits for those pieces to download.
- Needs free storage roughly equal to the video size.
- Codecs: whatever the TV supports through ExoPlayer (H.264/H.265/VP9, AAC/AC3 on most TVs).
  Some DTS/TrueHD audio tracks may not play.
- Only stream content you have the rights to.
