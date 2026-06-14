# Google Play Release Setup

The repository contains a manually triggered GitHub Actions workflow at
`.github/workflows/google-play-release.yml`. It builds a signed Android App
Bundle for version code `1` and uploads the AAB as a workflow artifact. It can
also upload to Google Play when the `publish_to_play` workflow input is enabled.

Actual secrets cannot be stored in the repository. Add them as GitHub repository
secrets in `CrownParkComputing/Ymir-Android`.

## Required Repository Secrets For Signed AAB Builds

| Secret | Purpose |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoded upload keystore used to sign the release AAB. |
| `ANDROID_KEYSTORE_PASSWORD` | Password for the keystore. |
| `ANDROID_KEY_ALIAS` | Alias of the upload key inside the keystore. |
| `ANDROID_KEY_PASSWORD` | Password for the upload key. |
| `IGDB_CLIENT_ID` | IGDB client ID embedded into release builds. |
| `IGDB_CLIENT_SECRET` | IGDB client secret embedded into release builds. |

## Optional Repository Secret For Play Uploads

| Secret | Purpose |
| --- | --- |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Plain JSON for the Play Console service account. Required only when `publish_to_play` is enabled. |

## Signing

The Gradle release build reads signing data from environment variables:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The GitHub Actions workflow decodes `ANDROID_KEYSTORE_BASE64` into
`android/app/signing/play-release.jks` and points `ANDROID_KEYSTORE_PATH` at
that temporary file. The `android/.gitignore` file excludes keystores and signing
folders so release credentials do not get committed.

## Play Console Access

The service account behind `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` must have access
to the app in Play Console. The package name uploaded by the workflow is:

`com.saturn_emu.android`

The app must already exist in Play Console before the automated upload can
publish to a track. For first-time releases, use the internal track first.

## Workflow Inputs

The `Google Play Release` workflow prompts for:

- `track`: `internal`, `alpha`, `beta`, or `production`.
- `status`: `draft`, `completed`, `inProgress`, or `halted`.
- `release_name`: the Play Console release name.
- `publish_to_play`: whether the workflow should upload the AAB to Google Play.
- `changes_not_sent_for_review`: whether Play Console changes are left pending.

The default path builds a signed AAB artifact without uploading to Play. For the
first Play upload, use the internal track with draft status.
