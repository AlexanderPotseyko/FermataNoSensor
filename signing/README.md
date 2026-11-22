# FermataNoSensor signing key

This is a new keystore for the personal FermataNoSensor build. It is not the upstream Fermata key. The keystore and these passwords are committed so later release builds can update the same installed app. Do not publish the keystore outside this private project.

| | |
|---|---|
| File | `signing/fermatanosensor.jks` |
| Alias | `fermatanosensor` |
| Store password | `BLyDgjFs36gYYZWlNEfClj` |
| Key password | `BLyDgjFs36gYYZWlNEfClj` |
| Certificate | `CN=FermataNoSensor, O=potseyko, OU=Personal` |

`settings.gradle` reads `signing/keystore.properties` and creates `fermatanosensor.jks` with `res/build_jks.gradle` if the file is missing. The generated key is deterministic for these passwords, so recreating the file produces the same key.

Build the arm64 Android Auto release:

```bash
export ANDROID_SDK_ROOT=<path to android SDK>
./gradlew :fermata:bundleAutoRelease -PABI=arm64-v8a
```

The release `applicationId` is `by.potseyko.fermatanosensor` (no `.auto` suffix).
