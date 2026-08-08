# ROProperties

**Current app version: 0.4.0**

ROProperties is a native Android Material 3 **property inspector and controlled override manager** for Android `ro.*` properties. Reading works without root; supported overrides require root.

It deliberately distinguishes the property-service string from the real Android/framework/hardware state. A successful `resetprop` is not presented as proof that a subsystem, device identity, verified-boot state, API level, encryption state, or hardware capability actually changed.

## 0.4.0 behaviour model

Every property receives an override policy in addition to its meaning/value explanation:

- **Spoofable** — identity-facing strings such as model, brand, device and build fingerprint. These default to **Persistent / next boot** because Android and apps commonly cache them.
- **Boot-time** — vendor/ODM/boot-oriented metadata where a live property-service write may not reconfigure the consumer.
- **Advanced** — properties that can be overridden but whose consumer behaviour is not guaranteed.
- **Read-only** — bootloader/AVB truth such as verified-boot and lock-state properties. ROProperties keeps these information-only instead of pretending a string changes trust state.
- **Dangerous** — core runtime/security/compatibility metadata such as zygote, crypto state, Treble/VNDK, API level and first API level. ROProperties intentionally refuses to override these.

Each row also shows an **apply strategy**: Live override, Restart-sensitive, Next boot, or Do not modify.

## Effective value checks

For canonical Android identity properties, ROProperties compares:

1. the current property-service / `getprop` value,
2. the mapped Android `Build.*` value cached by the currently running ROProperties process, and
3. any saved persistent override in `/data/adb/modules/roproperties/system.prop`.

If `getprop` says one model/fingerprint while `Build.*` still reports another, the UI shows **Framework cache differs** instead of calling the spoof fully effective.

A live-only override is never followed by a misleading reboot recommendation: reboot would discard it. **Reboot now** is offered only after a persistent override has actually been saved.

## Apply modes

- **Live override** — uses `resetprop` and verifies the property-service result with `getprop`. Existing framework/app processes can still retain cached values.
- **Persistent / next boot** — saves the override in the ROProperties root module. Preferred for identity/boot-sensitive values.
- **Live + persistent** — changes the property service now and saves the next-boot override. A reboot can still be required before fresh framework/app processes agree.

## Spoofing properties

The dedicated **Spoofing properties** category contains device/build identity values commonly read by software. Changing these strings does not turn one device into another and does not by itself change hardware-backed or cryptographic attestation.

## Import and export

Selection mode supports individual selection or **Select all** for the current category/search. Selected names and values export to a readable TXT document that can be imported again.

The importer accepts ROProperties exports, plain `ro.name=value` / `ro.name = value` lines, and Android `[ro.name]: [value]` dumps. Duplicate names use the last supplied value.

Before bulk apply, the importer analyses the profile and warns when:

- model/device/product identities disagree across partitions,
- boot/cache-sensitive values should use persistent + reboot,
- read-only/dangerous values will be intentionally blocked.

This is useful for ported ROMs where system and vendor partitions can legitimately identify different source devices: ROProperties shows the mismatch instead of silently assuming the profile is a clean single-device spoof.

## Knowledge engine

ROProperties discovers every exposed `ro.*` property and separately explains **what the property means** and **what its current value means**. Curated AOSP/common properties are labelled documented/known; unknown OEM/vendor properties use clearly labelled inference rather than fabricated certainty.

## Build

Kotlin + Jetpack Compose + Material 3, minSdk 26 / targetSdk 36.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

GitHub Actions runs unit tests and builds the debug APK on pushes and pull requests.

## Safety

ROProperties cannot make hardware, bootloader trust, encryption, partition layout, framework APIs, or security patches exist merely by changing a property string. Some properties are intentionally blocked because presenting them as ordinary editable settings would be misleading or unnecessarily dangerous.
