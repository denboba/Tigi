# Tigi production-readiness report

Branch `tigi-cards`. "Built" means the GitHub Actions build was green for that commit. **Nothing in
this report was run on a device**: there is no emulator or device in the build environment, so every
behaviour below is verified only by reading the code paths (marked *reasoned*) unless it says
*built*. Treat the manual checklist (`docs/TIGI_TEST_CHECKLIST.md`) as the real acceptance test.

## Phase 0: baseline audit (`git diff master...tigi-cards`)

| File | Issue | Severity | Fix |
|---|---|---|---|
| `res/xml/auth.xml`, `sync_contacts.xml`, `ContactsController` | Contacts account type stayed `org.telegram.messenger`: with the official app installed, the two apps share one authenticator; Tigi's contact sync fails or writes into Telegram's account | High | Own account type `com.tigi.app` (`BuildVars.CONTACTS_ACCOUNT_TYPE`) |
| `res/xml/auth_menu.xml` | "Account settings" in Android's account screen opened the official Telegram app | Medium | Targets `com.tigi.app` |
| `AndroidManifest.xml`, `contacts.xml`, `LaunchActivity`, `ContactsController` | Contact mime types `vnd.org.telegram.messenger.*`: both apps answer each other's "message/call" contact actions | Medium | `vnd.com.tigi.app.android.*` |
| `ImageLoader`, `SharedConfig`, `FilesMigrationService` | On Android ≤ 10 Tigi used `/sdcard/Telegram` (shared with the official app): either app's "clear cache" deletes the other's files, and the scoped-storage migration would **move Telegram's files into Tigi** | High | Own public folder `Tigi` (`BuildVars.PUBLIC_ROOT_DIR`); app-private folders unchanged |
| `BuildVars.SAFETYNET_KEY` | Telegram's SafetyNet key only works for Telegram's package: Firebase SMS verification fails then retries | Medium | Emptied: codes come by SMS / in-app |
| `LoginActivity` | "Sign in with Google" needs an OAuth client for `com.tigi.app`; it silently failed | Low | Hidden until `TIGI_GOOGLE_SIGN_IN` |
| `MediaDataController`, `ReactionsLayoutInBubble`, `TigiFeedBase` | Heart detected with `startsWith("❤")`: heart-on-fire / mending heart counted as likes | Medium | Exact match (`TigiHeart`) |
| `ChatMessageCell.isTigiCardMedia` | Full-width media used 32dp margins instead of the 64dp Telegram's own full-width case uses: with avatars (groups) or the share button the bubble ran off screen | High | Same margins as stock full-width |
| `MessageObject.GroupedMessages` "tigiStack" | Row heights computed from `displaySize` (wrong on tablets, landscape, split screen) | Medium | Cell sizes each row from its real width and aspect ratio (4:5 … 16:9) |
| `MessageDrawable` + `bubbleRadius` 20/28 | Bubble nine-patches are 50×40dp and clamp the radius to ~19dp: solid bubbles never got the set radius, selected/gradient bubbles did (visible jump) | Medium | Nine-patch size follows the radius |
| `AndroidUtilities.bold()` | `useSystemBoldFont` debug option became a no-op | Low | Option gives weight 500 back; default 650 kept |
| `DialogsActivity` channel → feed | Opening a channel as feed never marked it read (badge stuck); no way to join from search; on tablets the feed opened as a popup | High | Marks read on first page; Join button; tablets keep the chat; behind `TIGI_HOME` |
| `HomeFeedActivity` | 25 parallel `getHistory` (FLOOD_WAIT risk), callbacks after destroy, empty feed on cold start | Medium | 3 at a time, stop on FLOOD_WAIT, generation guard, reload when the chat list arrives |
| `TigiFeedBase` / `ChannelFeedActivity` | Albums split across pages became two cards; refused reactions stayed shown; no live reaction/delete updates; hard-coded English and no TalkBack labels; status-bar inset could be 0; no theme switch | Medium | All fixed |
| `ChatActivity` media-feed item | Only groups; on tablets channels had no way to the feed | Low | Groups: photos & videos; channels: "Feed view" |
| `UserObject`/`MessagesController` | Service account 777000 (login codes, security alerts) renamed "Tigi": users can't tell who sends codes, easier phishing | High (trust) | Real name kept |
| strings (all locales) | "unofficial Tigi client", "the official Tigi app is available for…", "download Tigi … from getdesktop.telegram.org", login-code texts | Medium | Restored and protected in `TigiBranding` |
| `TigiBranding` | "Telegram Premium" → "Premium" etc. | — | Now "Tigi Premium" etc., as requested |
| `SettingsActivity` | Paid rows hidden with `if (false && …)` | Low | `BuildVars.TIGI_PAID_FEATURES` (off) |
| Tab indexes (`MainTabsActivity`) | Checked every use inside and outside (LaunchActivity, LinkManager, DialogsActivity, TwoStep*, FolderBottomSheet, ViewPagerActivity): no external index use, mapping correct | — | Rebuilt data-driven in Phase 1 |
| Translations | All `strings.xml` valid XML, every changed string keeps its placeholders, `**`, links | — | Verified by script |
| `.github/workflows/build.yml` | Signing key only in the Actions cache (evictable; restorable by fork PRs) | High | Secret-backed keystore + encrypted backup (Phase 7) |

Built: run 15 green (`fcc2e767c`).

## Phases 1–7: what changed

1. **Navigation** (`TIGI_NEW_NAV`): Home | Reels | + | Chats | Profile, built from the flags; the old bar is the flag-off path. "+" is a button that is never selected (also after a long-press slide). Contacts: Chats header button + ⋮; Calls and Settings: Chats ⋮; Settings: Profile button + ⋮. App still starts on Chats because DialogsActivity's onResume asks for notification/contacts permissions and runs the storage-migration check. `TigiNavigation.openProfile` is the one place deciding where an avatar/name goes.
2. **Create & post** (`TIGI_POST`): Create sheet (Post, Reel, Story, Go live, Text). "Post as" over every channel the user can post in, last used first; personal channel on first post (private unless "public link" is ticked; taken links, too many public channels, FLOOD_WAIT, restricted accounts all have messages; never creates a second one). Composing is Telegram's own composer opened in the channel (gallery, editor, caption with entities, album order, schedule via long-press Send, silent), and returns after sending. Sending/failed posts show at the top of Home and the channel feed; failed ones open the chat for retry/delete. Card ⋮: view in chat, copy link, edit caption, pin, statistics, report, delete.
   Not possible as asked: a per-post "comments on/off" switch does not exist in Telegram; comments depend on the channel's linked discussion group (Info > Discussion).
3. **Feeds**: one inline VideoPlayer per screen, muted by default, autoplay only within Telegram's autoplay + auto-download settings, stops when scrolled away / recycled / screen paused, full screen in PhotoViewer; "View in chat" card for voice, round video, files, polls, stickers, location, contacts, games; forwarded-from line; links, @mentions, #hashtags open the right screens; comments open the discussion thread.
4. **Reels** (`TIGI_REELS`): vertical pager of videos ≤ 180 s from followed channels (`messages.search` video filter, round-robin over 6 channels per round, 3 requests at a time, de-duplicated by file), one player, only the next video preloaded (≤ 20 MB, auto-download rules), loop, tap pause, double-tap heart, long-press reactions, side actions, remembered mute, audio focus, pause on headphone unplug, released on low memory.
5. **Profile**: Tigi profile for channels and for people with a Telegram *personal channel* (counts, story ring, Join/Following, Message, Share, Info = Telegram's profile), Posts and Reels grids (video duration and album badges), tiles open the channel feed at that post. People without a personal channel keep Telegram's profile (block, report, call, notifications, shared media untouched). "My posts" on the own profile.
   Not done: Saved and Tagged tabs (no clean API mapping); the Profile tab itself is still Telegram's own profile.
6. **Live** (`TIGI_LIVE`): Telegram has no one-to-many live for personal accounts; Live is the channel video chat / live stream. Go live → Telegram's own start dialog (now / schedule / RTMP "start with") and GroupCallActivity (camera, mic, viewer count, end with confirmation, permissions and network handling are Telegram's). LIVE badge with viewer count on cards and the channel feed; tap joins.
7. **Hardening**: About screen (version/build, unofficial-app notice, GPL source link, Tigi + Telegram privacy policy, report a problem); Tigi privacy URL used in Settings when set; CI: keystore from secrets, encrypted key backup, release mode (AAB, arm64+armv7 APK, R8 mapping, versionCode), placeholder-Firebase warning; `docs/TIGI_RELEASE.md`, `docs/TIGI_TEST_CHECKLIST.md`.

### Verified vs reasoned

| Item | Status |
|---|---|
| Compiles | Built: Phase 0 in run 15; all phases (1–7 + sponsored) in run 20 (`819446990`), after two compile fixes found by runs 16 and 18 |
| Translation files valid, placeholders intact | Verified (script over all locales) |
| Tab index mapping in both layouts | Reasoned (traced every INDEX_/POSITION_ use) |
| Everything user-facing (feeds, video, reels, posting, live, profile, navigation, permissions, RTL, tablets, TalkBack, theme switch, process death, memory) | Reasoned only. Needs the manual checklist |
| Push notifications (FCM) end to end | Not verifiable here: needs the owner's Firebase project |
| Cold start not slower | Reasoned (start page unchanged; Home/Reels load only when shown) |

## Open risks, ranked

1. **No device testing yet.** All new UI is unexercised. Run the checklist before any public release.
2. **Signing key in the Actions cache** until the keystore secrets are set: eviction = every user must reinstall; fork PRs can restore base-branch caches. Do the key move in `TIGI_RELEASE.md` first.
3. **Push notifications** do not work with the placeholder Firebase config.
4. **Google Play policy**: digital goods (Telegram Stars/Premium/gifts) can still be bought from in-chat entry points via Telegram's invoice flow outside Play billing; review each entry point or keep Tigi off Play. User-generated content: report/block stay reachable (card ⋮ Report, Telegram's profile and chat menus) — confirm in the Play UGC questionnaire.
5. **Telegram API terms** (core.telegram.org/api/terms could not be fetched from the build environment; from knowledge, please re-read the current text): own api_id ✔; the name must not suggest an official app and the Telegram logo must not be used ✔ (About notice added; check splash/intro art and notification icons); do not mislead users about who they are talking to ✔ (777000 keeps its name); no spam, flooding or fake accounts ✔ (throttled feed loading); **sponsored messages in channels must be shown** — the classic chat and (since this round) the channel feed show them, with view and click reporting; Home and Reels mix many channels and show none — confirm with the current terms whether aggregated feeds need them; protect user data, publish a privacy policy (owner); keep the API hash secret from third parties (it is in the APK by necessity, never in the repo).
6. **Remaining Telegram trademarks in assets** (splash/intro animations, some notification icons, share texts in other locales) were not fully audited.
7. Bold font weight 650 may clip text in fixed-width controls on some devices (font scale pass needed).
8. Reels and Home use `messages.search` per channel: heavy users with many channels see only the 25–30 most recent; ranking is by date only.
9. Inline video audio focus is ExoPlayer's; behaviour with other apps' audio and Bluetooth needs a device test.

## What the owner must provide

- Firebase project with an Android app `com.tigi.app` → `GOOGLE_SERVICES_JSON` secret.
- Signing keystore in secrets + verified offline backup (`TIGI_RELEASE.md`).
- Tigi privacy policy URL and a support e-mail (`BuildVars.TIGI_PRIVACY_POLICY_URL`, `TIGI_SUPPORT_EMAIL`).
- Play listing assets and texts (stating "unofficial app using the Telegram API"), content rating, data safety form.
- Optional: Google OAuth client for `com.tigi.app` (login "Sign in with Google").
- Legal review of the GPL source offer and the Telegram API terms for your distribution.

## Rollback

Each feature is one flag in `BuildVars` (table in `TIGI_RELEASE.md`). Flip, push, wait for the green
build, ship. The signing key does not change, so users update in place.
