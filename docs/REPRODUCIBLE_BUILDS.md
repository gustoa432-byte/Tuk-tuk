# Reproducible / release builds (TukTuk)

Goal: a third party can rebuild an APK and compare digests with a published release.

## Current project flags

In `gradle.properties`:

```properties
# Pin ZIP entry timestamps for more stable APK/AAB contents (AGP).
android.experimental.enableSourceSetPathsMap=true
reproducible.apk.epoch=1719792000000
```

In `app/build.gradle.kts` packaging (when enabled):

- Prefer deterministic file order in the APK.
- Release uses R8 (`isMinifyEnabled = true`) with `proguard-android-optimize.txt` + `proguard-rules.pro`.

Set epoch via project property when needed:

```bash
./gradlew assembleRelease -Preproducible.apk.epoch=1719792000000
```

## How to verify

1. Build from the same git tag / commit as the published artifact.
2. Use the same JDK (Temurin 17) and AGP/Gradle versions from the repo wrappers / CI.
3. Compare:

```bash
sha256sum app/build/outputs/apk/release/Tuktuk.apk
apksigner verify --print-certs app/build/outputs/apk/release/Tuktuk.apk
```

4. For byte-identical APKs you also need identical signing material and identical R8 mapping inputs. Unsigned or re-signed builds will differ in the signature block even if DEX matches — compare `classes.dex` inside the APK if needed:

```bash
unzip -p Tuktuk.apk classes.dex | sha256sum
```

## R8 notes

- Release minify is on; keep rules live in `app/proguard-rules.pro` (Room, kotlinx.serialization, Compose).
- Mapping files under `app/build/outputs/mapping/release/` should be archived with each FOSS release for crash symbolication.
- Do not claim bit-identical APKs across machines until CI publishes a reproducible release job and verification script.

## CI

`.github/workflows/build.yml` currently builds **debug** (`assembleDebug`). A reproducible release pipeline is a follow-up: pin JDK, Gradle, and epoch, then upload SHA256 sums.
