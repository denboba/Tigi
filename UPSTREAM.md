# Keeping Nilak in sync with Telegram

Nilak is built on Telegram for Android (DrKLO/Telegram). Each Telegram release is merged into `tigi-cards`.

## How to update

```bash
git fetch https://github.com/DrKLO/Telegram.git master:refs/remotes/upstream/master
git merge --no-ff upstream/master
```

Resolve the conflicts (see below), keep `APP_PACKAGE=com.nilak.app` in `gradle.properties` and take Telegram's
`APP_VERSION_CODE` / `APP_VERSION_NAME` when they are higher than the last Play upload.

## Rules that keep merges small

1. **New Nilak work goes in Nilak files**, not in Telegram's: `org/nilak/**`, `org/telegram/ui/Tigi*.java`,
   `org/telegram/ui/Nilak*.java`, `TigiLilac`, `TigiFeedBase`, the `Nilak*` / `Tigi*` strings.
2. **In Telegram's files, only small hooks**: one `if (BuildVars.NILAK_…)` line or a call into a Nilak class,
   with a `// Nilak:` comment, close to Telegram's code and never reformatting it.
3. **Every feature has a flag** in `BuildVars` (`NILAK_*` / `TIGI_*`), so a conflict can be resolved by taking
   Telegram's side and re-adding the hook.
4. **New strings go at the end of the Nilak block** in `strings.xml`; Telegram's own strings are not edited
   (the word Telegram becomes Nilak at runtime in `TigiBranding`; texts that must say Telegram are listed there).
5. When Telegram removes or rewrites something Nilak patched, prefer Telegram's new code and drop the patch
   unless the feature still needs it.

## Where conflicts usually happen

| File | Nilak hook |
| --- | --- |
| `ChatMessageCell` | smart fold (`nilakFold`), Barruu card (`nilakBarruu`), album card (`isTigiCardMedia`) |
| `MessageObject` | `nilakSmartFold`, `tigiStack` albums |
| `ChatActivity` | compose screen background (`nilakFeedLook`), `tigi_action`, Barruu tap opens the reader |
| `ChatAttachAlert` | Barruu button next to Article (`LAYOUT_TYPE_BARRUU`) |
| `Theme`, `ThemeColors`, `darkblue.attheme` | lilac colours, lilac night (`TigiLilac.tintNight`, `createNilakNightWallpaper`) |
| `SettingsActivity` | lilac page, hidden version line |
| `AndroidManifest.xml` | removed permissions (background location, call log) for Google Play |
| `MainTabsActivity` | Nilak bottom bar: Home, Reels, Communities (`TIGI_COMMUNITIES_TAB`), Read, Create, Chats, Profile |
| `GlassTabView` | `HOME`, `REELS`, `COMMUNITIES` tab icons |
| `DialogsActivity`, `DialogCell`, `ChatActivity`, `ProfileActivity`, `ChatActionCell` | a community opens `NilakCommunityActivity` (`NilakCommunityActivity.open`), Telegram's `CommunitySheet` when the flag is off |
| `BuildVars` | Nilak flags and links |
| `gradle.properties` | package name and version |
