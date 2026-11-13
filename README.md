# Linux AI for Android

An Android AI coding chat with an app-private PRoot + Alpine Linux ARM64 userspace sandbox. The UI is styled after the dark chat screenshots supplied in the request.

## Included

- OpenAI-compatible provider setup: API Base URL, API key and model ID.
- Model discovery via GET /models.
- Chat completions with tool calling via POST /chat/completions.
- API keys encrypted using an Android Keystore AES-GCM key.
- DuckDuckGo HTML search for current documentation, software versions and assets.
- Tool access to Linux commands, file read/write/list and downloadable artifacts.
- Persistent chat history.
- Foreground Android service with an ongoing notification and Stop action. Closing the chat or swiping the Activity from Recents does not intentionally stop the service. The in-app Exit command explicitly stops it.
- On first task, downloads the ARM64 PRoot runtime and official Alpine Linux minirootfs into private app storage.

## Important technical limits

This is NOT a hardware virtual machine and does not boot an independent Linux kernel. It is a real Linux userspace/root filesystem running through PRoot on the Android kernel. It has its own files and package database but shares the Android kernel. The first build is a headless coding environment; it does not include XFCE or a desktop session.

Android can still stop foreground services because of force-stop, severe memory pressure, OEM battery management, reboot or permission changes. No ordinary Android app can promise an uninterrupted ten-hour job against those system actions. Linux AI keeps a foreground notification and acquires a partial wake lock while a task is actively running, which uses battery and mobile data. Do not use Android Settings > Force stop if you expect a task to continue.

**No Android root access is required.** The app stores the Linux root filesystem in its private app directory and launches it through PRoot. The `-0` option only emulates root identity inside that userspace; it does not grant Android superuser privileges, modify system partitions, or require Magisk. Alpine's `/bin/sh` is validated by actually launching it through PRoot, rather than checking it with Android's host-side `File.exists()` (which can incorrectly reject absolute symlinks in the guest rootfs).

The rootfs remains installed after a task finishes. Exit stops the service but does not delete the sandbox. Uninstalling the app removes app-private files. A manual sandbox reset UI and automatic cleanup of the rootfs are not included in this initial release.

## Build

Requires JDK 17, Android SDK platform 35 and Gradle 8.9.

    gradle :app:assembleRelease

APK output:

    app/build/outputs/apk/release/app-release.apk

GitHub Actions builds the release APK and publishes successful builds from main as GitHub Releases. For installability in this test build, the APK uses the Android debug signing key. Replace this with a private release key before production distribution.

## First launch

1. Enter an OpenAI-compatible Base URL, e.g. https://api.openai.com/v1.
2. Enter your API key.
3. Tap Load available models, choose a model, then Save.
4. Send a coding task. The first task downloads PRoot and the Alpine ARM64 rootfs; this needs network access, storage and time.
5. Close the chat normally to leave the foreground service active. Use Exit in the app menu or Stop in the notification to stop it.

## Runtime sources and licensing

- PRoot is downloaded at runtime from ahmed-alnassif/proot GitHub Releases; it is not bundled here. Check the upstream repository for its AGPL-3.0 terms and notices.
- Alpine minirootfs is downloaded from the official Alpine Linux CDN; consult Alpine's package and distribution notices.
- The Android app source in this repository is MIT licensed (see LICENSE).
