# Open WebUI Android App

A lightweight Android app for your self-hosted [Open WebUI](https://github.com/open-webui/open-webui) server. It wraps your server in a native app with login/cookie persistence, file uploads, microphone/camera access for voice, and background "response ready" notifications.

On first launch it asks for your server address (for example `https://chat.example.com` or `http://192.168.1.50:3000`). Press **Back twice** on the first page to open settings.

**Requires Android 8.0 (API 26) or newer.**

## Features

- Loads your Open WebUI server in a full-screen native shell
- Persistent login, file upload/download, microphone and camera for voice mode
- **Notifications** when a response finishes while the app is in the background
- Links to other sites open in your browser
- See [What's new](#whats-new) for the latest additions

## What's new

### v1.2

- **Share to Open WebUI:** shared text opens a new chat with the text filled in. Shared files and images (3 MB max) are attached to a new chat. That attach step works by simulating a file selection in the page, which is the least certain part.
- **Screen lock:** this asks for your phone's PIN, pattern or biometric when you open the app, and again after the app has been in the background for more than a minute. Turn it on in settings.
- **Error screen:** a Retry button, plus an optional fallback server address that's tried automatically if the main one fails.
- **Shortcuts:** a long-press "New chat" shortcut on the app icon and a "New chat" home-screen widget.
- **Voice and screen:** microphone permission is asked for at first launch, and there's a "Keep screen on" option.
- **Polish:** a new icon with a themed variant, a splash screen, and light and dark themes matching your phone. Settings are now on Back twice.

### v1.1

- **Response-finished notifications:** a quiet foreground service keeps the app alive in the background and posts a notification when a reply completes. It watches for Open WebUI's stop-generating button to disappear, so a future Open WebUI update that renames that element could stop notifications.

### v1.0

- Initial release: WebView wrapper with server address prompt, uploads, voice permissions and external link handling.

## Install

1. On your Android phone, open the [latest release](https://github.com/Msjcolli93/OpenWebUI-Apk/releases/latest) and tap the `.apk` file under **Assets**.
2. Open the downloaded file. Allow installs from your browser if Android asks.
3. Enter your Open WebUI server address.

Releases are signed with a fixed key, so new versions install over old ones. If you previously installed a build from Android Studio or an older artifact, uninstall it first (different signing key).

## Build

**Android Studio:** open this folder, let Gradle sync, then **Build > Generate App Bundles or APKs > Generate APKs**. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions:** every push runs the **Build APK** workflow, which uploads a debug-signed APK as an artifact. You can also run it manually from the Actions tab.

**Signed release (optional):** place your keystore at `app/owui-release.jks` and set `OWUI_KS_PASS` (and optionally `OWUI_KEY_ALIAS`, `OWUI_KEY_PASS`) before running `gradle assembleRelease`. Keystores are git-ignored; never commit yours.

## Notes and limitations

- Notifications can be cut off by aggressive battery savers. If they stop, set the app's battery usage to **Unrestricted** in Android settings.
- The screen lock uses Android's device credential prompt (PIN, pattern or biometric, depending on your phone).
- Debug-signed builds and release-signed builds can't be installed over each other; uninstall one before installing the other.
- Features were built and compiled but have had limited on-device testing.

## Project layout

- `app/src/main/java/com/openwebui/app/MainActivity.java`: WebView, settings, share handling, lock, notifications
- `KeepAliveService.java`: foreground service for background notifications
- `NewChatWidget.java`: home-screen "New chat" widget
