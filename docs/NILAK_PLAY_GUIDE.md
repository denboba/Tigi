# Nilak on Google Play: release and open testing guide

A step-by-step help for publishing a new Nilak build: what to do after the release build, how to
submit to open testing, how to set up the test account Google's reviewers use, and what to write in
the release notes.

---

## 1. After the release build

The release build is the **Build Tigi APK** workflow started with **release = true** (Actions tab →
Build Tigi APK → Run workflow → branch `tigi-cards` → tick *release*). When it is green:

1. Open the run page and scroll to **Artifacts**.
2. Download **tigi-release**. It contains:
   - `*.aab`: the file you upload to Google Play.
   - `mapping.txt` (R8 mapping): lets Play show readable crash reports.
3. The **tigi-apk** artifact is the APK for installing directly on a phone (for your own testing,
   not for Play).

Before uploading, install the APK from the same run on a phone and check the main screens quickly
(Home, Reels, Communities, Chats, Profile, posting, a call). See `docs/TIGI_TEST_CHECKLIST.md`.

### Version numbers

- Every upload to Play needs a **higher version code** than the last one, on any track.
- By default the build uses `APP_VERSION_CODE` from `gradle.properties` (now **7303**, from Telegram
  13.2.2). The bundle's real version code is `7303 * 10 + 1 = 73031`.
- To upload again without a new Telegram merge, run the release with **version_code** set one higher
  than the last one you uploaded (for example `7304`, then `7305`...).
- Keep a note of every version code you upload (see the table at the end).

---

## 2. Upload to Play Console

1. Go to [play.google.com/console](https://play.google.com/console) → **Nilak**.
2. Left menu → **Test and release** → **Testing** → choose the track:
   - **Internal testing**: up to 100 people you invite, available in minutes, no full review.
     Use it first to check the Play build.
   - **Closed testing**: a list of testers (email list or Google Group).
   - **Open testing**: anyone can join from the store listing.
3. **Create new release**.
4. **App bundles** → **Upload** → choose the `.aab`.
5. **Release name**: filled in automatically (for example `73031 (13.2.2)`). You can keep it.
6. **Release notes**: see section 4.
7. **Next** → read any warnings → **Save** → **Send for review** (open and closed testing) or
   **Start rollout** (internal testing).
8. After upload, open **App bundle explorer** → the new version → **Downloads** tab →
   **ReTrace mapping file** → upload `mapping.txt` (if Play did not pick it up from the bundle).

> Note for personal developer accounts created after November 2023: Google requires a **closed test
> with at least 12 testers for 14 days in a row** before you can apply for production access.
> Open testing and production unlock after that. Check **Dashboard** in Play Console; it shows
> what is still required for your account.

---

## 3. Open testing: what to set before submitting

### 3.1 The open testing track

**Test and release → Testing → Open testing**:

- **Countries / regions**: choose where testers can join (for example Ethiopia, Kenya, USA,
  Europe...).
- **Testers**: open testing has no list; anyone can join from the store page. You can set a
  maximum number of testers.
- **Feedback channel**: an email or URL testers use to send feedback (for example
  `bitpyrus@gmail.com` or the Nilak bot `t.me/NilakAppBot`).

### 3.2 App content (must be complete before review)

Left menu → **Policy and programs** → **App content**. Each item must show *Completed*:

| Item | What to set for Nilak |
|---|---|
| **Privacy policy** | `https://denboba.github.io/Tigi/privacy-policy.html` |
| **App access** | *All or some functionality is restricted* plus the reviewer account (section 5) |
| **Ads** | *No, my app does not contain ads* (unless you add ads) |
| **Content rating** | Questionnaire: category *Social / Communication*; users can talk to each other: **Yes**; users can share photos/videos: **Yes**; shares location: **Yes** (location sharing in chats) |
| **Target audience** | 18+ (or 13+ if you are sure; younger audiences bring strict extra rules) |
| **Data safety** | Collected: phone number, name, photos/videos and messages (handled by the messaging servers to deliver them), contacts (if the user syncs them), crash logs. Encrypted in transit: **Yes**. Users can request deletion: **Yes** (account deletion in Settings) |
| **Government app / Financial features / Health** | No |
| **News app** | No |

### 3.3 Store listing

**Grow users → Store presence → Main store listing**:

- App name: **Nilak**.
- Short description (max 80 characters), for example:
  *Social feed, reels and communities with fast private chats.*
- Full description: what Nilak does (Home feed, Reels, Communities with Ask, Read, chats,
  calls, channels). Say clearly that Nilak is an **independent app** and is not affiliated with
  any other messaging company (do not use other companies' names or logos).
- Graphics: icon 512×512, feature graphic 1024×500, at least 2 phone screenshots (Home, Reels,
  Communities, Profile, Chats).

### 3.4 Review time

The first open-testing review can take **several days** (often 1–7). Later updates are usually
faster. You will get an email; the status also shows under **Publishing overview**.

---

## 4. Release notes (what to write)

Release notes are written per language, at most **500 characters** each, between language tags:

```
<en-US>
...English notes...
</en-US>
<om-ET>
...Afaan Oromoo notes...
</om-ET>
<am-ET>
...Amharic notes...
</am-ET>
```

Keep them short and about what the user sees; no technical words.

### Notes for this release (13.2.2)

```
<en-US>
What's new in Nilak:
• Communities: open a community to see all its chats, unread messages and its Ask board
• A cleaner, more compact profile
• Tap your username to switch between your accounts
• Unfollow from any profile
• Manage your channels, groups and bots from your profile
• Reels show the whole video
• Smoother likes and reactions
• Faster, more reliable posting and many fixes
</en-US>
```

Ask a native speaker to translate for `om-ET` and `am-ET` (keep the same lines).

### Template for later releases

```
<en-US>
What's new in Nilak:
• (biggest new feature, one line)
• (second change)
• (third change)
• Fixes and speed improvements
</en-US>
```

---

## 5. The test account for Google's reviewers (App access)

Nilak needs a login (phone number + one-time code), so reviewers need an account they can use.

### 5.1 Prepare a review account

1. Use a **separate phone number** only for reviews (a spare SIM you keep in a spare phone). Do not
   use your personal account.
2. Log in to Nilak with it and set it up: name *Nilak Review*, a profile photo, follow a few
   channels, join a community, and make one post, so reviewers see real content.
3. **Turn off two-step verification** on this account, or give the password in the instructions.
4. **Login code:** reviewers cannot receive the code sent to your spare phone. The options:
   - Add a **login email** to the account (Settings → Privacy and Security → *Login Email*, if
     your account offers it) using a new, dedicated mailbox, and give reviewers the mailbox
     login. *Not guaranteed:* the messaging server decides whether a code goes to the email,
     SMS or the app.
   - Keep the spare phone switched on and logged in, so the code arrives in the app there, and
     state a contact (email/phone) reviewers can use to get it quickly. Reviewers often do not
     wait, so expect a possible rejection with *"login credentials missing"*.
   - If Google rejects for this reason, tell me: a **review demo mode** (a fixed review number
     that opens a demo account) can be added to the app.

### 5.2 Where to edit it in Play Console

**Policy and programs → App content → App access → Manage** (or **Edit** if already set):

1. Choose **All or some functionality in my app is restricted**.
2. **Add instructions** (or edit the existing entry):
   - **Name**: `Nilak review account`
   - **Username / phone**: the review phone number with country code, for example `+2519XXXXXXXX`
   - **Password**: the 2-step password if you kept it on, otherwise write `none`
   - **Any other information**: the text below
3. **Save**. Editing App access does not need a new build; it is used for the next review.

Text for *Any other information* (edit the brackets):

```
Nilak logs in with a phone number and a one-time code.
1. Open Nilak and tap "Start Messaging".
2. Enter the phone number above.
3. The code is sent to the login email [review mailbox] (password: [mailbox password]).
   If it is not there, contact [your email / phone] and we reply within minutes.
4. After login: Home shows posts from followed channels, Reels shows short videos,
   Communities shows communities and their Ask boards, Chats has messages and calls,
   Profile shows the account and its posts.
No payment or subscription is needed for any feature.
```

Update this entry whenever the number, mailbox or password changes.

---

## 6. Checklist before pressing "Send for review"

- [ ] Release build green; APK from the same run tested on a phone.
- [ ] Version code higher than the last upload (table below).
- [ ] `.aab` uploaded, mapping uploaded.
- [ ] Release notes written (at least `en-US`).
- [ ] App access account works: log out and log in again with it yourself.
- [ ] App content all *Completed* (privacy policy, data safety, content rating, target audience,
      ads).
- [ ] Store listing has screenshots of the current design.
- [ ] Push notifications work on the release APK (message from another account with the app
      closed).
- [ ] The source code of this exact version is public on GitHub (GPL; the `tigi-cards` branch).

## 7. Uploads so far

| Date | Track | Version name | Version code (`version_code` input) | Notes |
|---|---|---|---|---|
| (earlier) | | | 7241 | last known upload |
| 2026-11-16 | | 13.2.2 | 7303 | Communities cards, compact profile |

## 8. If something goes wrong

- **Play says the version code is already used**: run the release again with `version_code` one
  higher.
- **Play rejects for login**: see section 5.1.
- **A feature misbehaves after release**: it can be switched off with its flag in `BuildVars.java`
  (see `docs/TIGI_RELEASE.md`, *Rollback with feature flags*), then build and upload a new version.
- **Signing error on upload**: the bundle must be signed with the same upload key as before. The
  key comes from the `TIGI_KEYSTORE_B64` secret (or the branch's Actions cache); never build a
  release from another branch.
