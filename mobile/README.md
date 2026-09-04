# Switchboard Mobile

Native Android client application built with **Kotlin** and **Jetpack Compose**.

## Features

- Scan QR code or enter desktop-generated code to establish secure pairing.
- Manage and switch between multiple connected desktop systems.
- Control remote system brightness, volume, DDC/CI monitor settings, and media playback.
- Send and receive files securely with end-to-end encryption.
- Remember trusted desktop connections for seamless auto-reconnect.

## Architecture

```
mobile/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/switchboard/app/
│   │       └── MainActivity.kt
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Development

Open the `mobile/` directory in Android Studio and build the project with Gradle.
