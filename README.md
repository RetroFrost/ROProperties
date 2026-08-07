# RO Properties

A native Android root utility that discovers and explains every `ro.*` system property exposed by the current device. The interface is built entirely with Jetpack Compose and Material 3, including dynamic colour on Android 12 and newer.

## What it does

- Lists every `ro.*` key reported by `getprop`, including OEM and vendor-specific properties.
- Searches names, values, and descriptions and filters by category.
- Provides curated descriptions for common AOSP properties and scoped prefix descriptions for unknown OEM keys.
- Flags caution, high-risk, and critical properties before editing.
- Applies runtime-only changes with `resetprop`.
- Creates persistent overrides in `/data/adb/modules/roproperties_persist` and applies them to the current boot too.
- Shows which values currently have a persistent override.
- Makes a snapshot before every edit, retains the newest ten, and can refill an editor from the newest snapshot.
- Includes a GitHub Actions workflow that builds a debug APK.

## Requirements

- Android 8.0 or newer.
- Root access through a solution that exposes `su`.
- A working `resetprop` command. Magisk provides it; other root solutions may provide it through a module.
- A module system compatible with `/data/adb/modules` for persistent mode.

The app can still read and explain properties without root. Editing remains disabled until both root and `resetprop` are available.

## Editing modes

**Runtime** calls `resetprop -n` and verifies the resulting live value. The change is lost at reboot.

**Persistent** updates a small root module containing `system.prop` and a boot service, then attempts the same runtime change. Removing a persistent override does not alter the current live value; it takes effect on the next reboot or after another runtime edit.

## Safety

Changing read-only properties is inherently risky. ABI, zygote, hardware, API-level, Verified Boot, and security properties can prevent Android or apps from starting. Product identity, fingerprints, serial numbers, and patch levels can break app compatibility or cause inconsistent identity reporting.

Keep a known-good boot image or custom recovery available. The generated persistence module can be disabled by creating its `disable` file or removed from recovery if a value prevents boot.

## Build

Open the project in a current Android Studio installation and build the `app` configuration, or run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can also run the **Build Android app** workflow and download the `ROProperties-debug` artifact.

## Stack

- Kotlin
- Jetpack Compose
- Material 3
- Android Gradle Plugin 8.11.1 / Gradle 8.13
- minSdk 26 / targetSdk 36

No analytics, internet permission, ads, or external root library is used.
