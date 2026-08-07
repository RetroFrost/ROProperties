# ROProperties

ROProperties is a native Android Material 3 app for inspecting and editing Android `ro.*` system properties on rooted devices.

Unlike property viewers that only recognise a small hard-coded list, ROProperties has a knowledge engine that separately explains **what each property means** and **what its current value means**. Known AOSP properties get curated definitions; OEM/vendor and undocumented properties get clearly labelled inference instead of a fake definitive answer.

## Features

- Discovers every `ro.*` property exposed by Android's property service.
- ChatGPT-inspired Android navigation: compact two-line menu, side drawer, neutral light/dark surfaces and a cleaner property feed.
- Real property categories instead of a single flat list.
- Dedicated **Spoofing properties** category for build/device identity values commonly read when software identifies the device.
- Additional Build & identity, Hardware, OEM & vendor, and Runtime & debug categories.
- Search plus documented/known/inferred/unknown confidence filtering from the drawer.
- Property meaning, current-value meaning, known alternative values, origin/namespace, consumers, edit behaviour, reboot notes and risk level.
- Runtime editing through root `resetprop` when supported.
- Persistent editing through a Magisk-compatible `system.prop` module in `/data/adb/modules/roproperties`.
- Runtime + persistent mode.
- Explicit warnings for compatibility/security/boot-sensitive properties.
- Neutral Material 3 light and dark themes.

## Spoofing properties

The Spoofing properties category groups identity-facing values such as build fingerprints, product model/manufacturer/brand/device/name, build IDs, build tags/types and version metadata across Android's system/vendor/product-style namespaces.

The category is intentionally descriptive rather than promising that changing a property will bypass an integrity, compatibility or security check. Different apps and Android components can use additional signals beyond `ro.*` values.

## Important editing behaviour

Modern Android makes `ro.*` properties immutable after boot. ROProperties therefore does **not** pretend that ordinary `setprop` can edit them. Runtime changes require a root implementation exposing `resetprop`. Persistent changes require a root framework that supports modules and `system.prop` (for example a Magisk-compatible module environment).

A changed property can also be cached by Android or a vendor process. A successful `resetprop` changes the property-service value, but it does not guarantee that a component which already read the old value will reconfigure itself.

## Build

The project uses Kotlin, Jetpack Compose and Material 3.

```bash
gradle assembleDebug
```

GitHub Actions runs unit tests and builds a debug APK on pushes and pull requests.

## Safety

Changing build identity, API level, verified-boot, ABI, zygote, partition, security or vendor properties can break boot, framework behaviour, telephony, app compatibility or integrity checks. ROProperties intentionally shows risk and confidence rather than promising that spoofing a string enables the feature represented by that string.
