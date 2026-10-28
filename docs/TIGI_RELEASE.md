# Tigi release guide (owner)

Tigi is built only by GitHub Actions (`.github/workflows/build.yml`) on branch `tigi-cards`.
Every push builds a signed arm64 APK (artifact `tigi-apk`). A manual run with **release** checked
also builds the Play bundle (AAB, all ABIs), an APK with arm64 + armv7, and keeps the R8 mapping
(artifact `tigi-release`, 90 days).

## Repository secrets

| Secret | Needed | What it is |
|---|---|---|
| `TG_API_ID`, `TG_API_HASH` | yes | Your app credentials from my.telegram.org. Never commit them. They are compiled into the APK (every Telegram client ships them; they identify the app, not a user). |
| `GOOGLE_SERVICES_JSON` | for push | The full `google-services.json` of your Firebase project. It must contain an Android client for `com.tigi.app`. Without it the build uses a placeholder: no background push, messages arrive only while the app is open. CI shows a warning. |
| `TIGI_KEYSTORE_B64`, `TIGI_KEYSTORE_PASSWORD`, `TIGI_KEY_ALIAS` | strongly recommended | The signing key as base64 (`base64 -w0 tigi.keystore`), its password, alias (default `tigi`). |
| `TIGI_KEY_BACKUP_PASSPHRASE` | once | Passphrase used to encrypt the key backup export. |

### Signing key: move it out of the cache (do this first)

Until the keystore secrets exist, the key lives only in the Actions cache (`tigi-signing-v1`).
GitHub evicts caches that are unused for 7 days or when the repo exceeds 10 GB, and a new key
means every user has to uninstall and reinstall. A pull request from a fork can also restore
caches of the base branch, so the cache is not a safe place for a release key.

1. Set `TIGI_KEY_BACKUP_PASSPHRASE` (a long random passphrase, keep it in a password manager).
2. Run the workflow manually with **export_signing_key** checked.
3. Download artifact `tigi-signing-backup-encrypted` (kept 3 days) and decrypt it:
   `openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in tigi-signing.tar.enc -out tigi-signing.tar && tar xf tigi-signing.tar`
4. Store `tigi.keystore` and `pw` in two separate safe places (password manager + offline copy).
5. Set `TIGI_KEYSTORE_B64` = `base64 -w0 tigi.keystore`, `TIGI_KEYSTORE_PASSWORD` = contents of `pw`, `TIGI_KEY_ALIAS` = `tigi`.
6. From then on builds sign with the secret; the log says "Signing with the keystore from secrets".
7. For Google Play, enrol in Play App Signing and upload this key as the upload key.

## Versions

`gradle.properties` keeps Telegram's `APP_VERSION_NAME`/`APP_VERSION_CODE`; the APK versionCode is
`APP_VERSION_CODE * 10 + 9` (afat) or `+ 1` (bundle). Every Play upload needs a higher versionCode:
run the release workflow with **version_code** set (for example `7238`, then `7239`, ...). When
merging a newer Telegram upstream, take its `APP_VERSION_CODE` only if it is higher than the last one
you uploaded.

## Before a store release

- [ ] Keystore in secrets (above) and a backup verified by decrypting it once.
- [ ] Real `GOOGLE_SERVICES_JSON`; send a message to a test account with the app in the background and screen off; a notification must arrive.
- [ ] `BuildVars.TIGI_PRIVACY_POLICY_URL` and `TIGI_SUPPORT_EMAIL` filled in (Play requires a privacy policy; "Report a problem" otherwise opens a public GitHub issue).
- [ ] Optional: register an OAuth client for `com.tigi.app` + your signing SHA-1 and set `TIGI_GOOGLE_SIGN_IN = true`, otherwise "Sign in with Google" stays hidden in login.
- [ ] Play listing: name, icon, screenshots, short/long description that says Tigi is an unofficial app using the Telegram API, content rating questionnaire (user-generated content, chat), data safety form (phone number, contacts if synced, messages handled by Telegram's servers).
- [ ] Manual regression pass (`docs/TIGI_TEST_CHECKLIST.md`) on the release APK.

## Rollback with feature flags

All flags are in `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`. Turning one
off and pushing gives a build that behaves like the release before that feature:

| Flag | Off means |
|---|---|
| `TIGI_NEW_NAV` | the previous bar: Home, Chats, Contacts, Calls/Settings, Profile |
| `TIGI_HOME` | no Home tab, channels open as normal chats, no feed/Tigi profile (Telegram's own UI) |
| `TIGI_POST` | no Post/Reel/Text in the Create sheet (Story/Live remain if on) |
| `TIGI_REELS` | no Reels tab |
| `TIGI_LIVE` | no Go live, no LIVE badges |
| `TIGI_PAID_FEATURES` | (off by default) Premium/Stars/Business/Gift rows hidden in Settings |

With `TIGI_NEW_NAV`, `TIGI_HOME`, `TIGI_POST`, `TIGI_REELS` and `TIGI_LIVE` all off, the UI is
Telegram's stock UI plus the always-on Tigi look: theme, rounder bubbles, full-width media and
one-photo-per-row albums in channels/groups, heart first among reactions, branding and wording.

## GPL

Tigi is a fork of Telegram for Android (GNU GPL v2 or later). Every APK you distribute must come
with the complete corresponding source: keep this repository public and the tag/commit of each
release reachable. The About screen links to `BuildVars.TIGI_SOURCE_URL`.
