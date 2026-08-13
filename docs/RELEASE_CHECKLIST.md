# Release checklist (Qq)

Short list to keep a release honest and to stop documentation from drifting away from the build.

## Single source of truth for the version

`versionName` / `versionCode` in **`app/build.gradle.kts`**. Nothing else declares the current version.

```bash
grep -E 'version(Name|Code)' app/build.gradle.kts
```

Rule: if a document and `app/build.gradle.kts` disagree, the build file is right and the document is a bug.

Docs should **reference** the version rather than copy it. Only two places may legitimately contain a literal version number:

| Place | Why |
|-------|-----|
| `app/build.gradle.kts` | the source of truth |
| `TERMS.md` header | a terms document needs a fixed version/date it was written for |
| `docs/VERSIONS.md` tag table | historical tags, which by definition do not change |

Everywhere else, write "see `versionName` in `app/build.gradle.kts`".

## Steps

1. Bump `versionName` + `versionCode` in `app/build.gradle.kts`.
2. Add the tag to the table in [`VERSIONS.md`](VERSIONS.md) (history only — do not declare "current version" there).
3. If user-visible behaviour, privacy or transport handling changed, update whichever applies: [`../README.md`](../README.md), [`PRIVACY.md`](PRIVACY.md), [`SECURITY.md`](SECURITY.md), [`THREAT_MODEL.md`](THREAT_MODEL.md).
4. If terms-relevant behaviour changed (auth, moderation/reports, blocking, what the gateway receives), update [`../TERMS.md`](../TERMS.md) **and** its version/date header.
5. Build release, verify signing (see [`SIGNING.md`](SIGNING.md) / [`OFFICIAL_BUILD.md`](OFFICIAL_BUILD.md)) — the release keystore must be the existing one, never regenerated.
6. Tag, push, publish the APK.

## Truthfulness pass (quick)

Before publishing, check that no document claims any of these, because none of them are true:

- guaranteed or instant delivery;
- absolute security, or anonymity;
- compliance with any jurisdiction's legislation;
- a global ban-list as a working product feature;
- public channels/feeds as part of the product;
- that the gateway only ever receives ciphertext (true for text; see the image caveat in [`PRIVACY.md`](PRIVACY.md));
- that in-app deletion of all user data, or gateway account deletion, exists.

## CI helper

`.github/workflows/build.yml` runs a `docs-version-check` job that compares the `TERMS.md` header with `versionName` from `app/build.gradle.kts` and lists version literals found in docs. It is **advisory**: it prints warnings in the job summary and does not fail the build.
