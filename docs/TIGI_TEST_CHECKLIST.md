# Tigi manual regression checklist

Run on the APK from the latest green build. Mark each item ✅ / ❌ / n/a and note the device.
Devices to cover: one low-end phone (2 GB RAM, Android 8–10), one recent phone (Android 14+),
one tablet or foldable. Repeat the starred (★) items in dark theme, in RTL (Arabic or Hebrew),
with font scale 200% and with TalkBack on.

## 1. Install and coexistence
- [ ] Install next to the official Telegram app: both run, both log in, contacts sync in both (Settings > Accounts shows a Tigi account and a Telegram account).
- [ ] Update over the previous Tigi build without uninstalling (same signing key).
- [ ] Android 10 or older: Telegram's own `/sdcard/Telegram` files are untouched after using Tigi and after "Clear cache" in Tigi.

## 2. Stock Telegram functions (no regressions)
- [ ] Login with SMS / in-app code; 2-step password; log out and in again; add a second account and switch (long-press Profile tab).
- [ ] 1:1 chat: text, emoji, stickers, GIFs, photo, album, video, file, voice and round video messages; reply, forward, edit, delete, pin.
- [ ] Group: mentions, replies, reactions, polls, member list, admin actions.
- [ ] Secret chat: start, send media, self-destruct timer, screenshot notice.
- [ ] Voice and video calls 1:1 (incoming and outgoing, app in background); group voice chat.
- [ ] Search: global, in chat, hashtags.
- [ ] Notifications: message with app in background and screen off (needs real Firebase config), reply from the notification, mute a chat.
- [ ] Folders: create, edit, long-press Chats tab to switch folder.
- [ ] Settings: every row opens (account, chat settings, privacy, notifications, data, folders, devices, power, language, About Tigi).
- [ ] Backups/export: Settings > Data > export (if available), Saved Messages.
- [ ] Language pack: switch to German and Arabic; strings readable, no "Telegram" where Tigi is meant, "Telegram" kept where the official app is meant (login code text, desktop download).

## 3. Navigation (★)
- [ ] New bar: Home | Reels | + | Chats | Profile. App starts on Chats. Back from any tab goes to Chats, then exits.
- [ ] "+" never stays selected, also after a long-press slide across the bar and releasing on "+".
- [ ] Chats header: Contacts button; ⋮ menu has Contacts, Calls, Settings, New group, Saved messages.
- [ ] Profile tab: Settings button and Settings in ⋮; My posts; long-press shows the account switcher.
- [ ] Rotate on every tab; split screen; process death (Developer options > Don't keep activities) and return.
- [ ] Flags off build (`TIGI_NEW_NAV = false`): old bar with Contacts and Calls/Settings works as before.

## 4. Create and post
- [ ] No channel yet: "+" > Post > create personal channel (private by default; with "Public link" ticked: link set, or a clear message if taken).
- [ ] Post a photo, an album of 10 with reordering, a video (large, > 100 MB), a text post with a link and custom emoji, a scheduled post.
- [ ] Post as admin in a channel you don't own.
- [ ] Airplane mode while posting: card shows "Not posted", tap opens the chat with retry/delete; retry works when back online.
- [ ] App to background during a video upload: upload finishes.
- [ ] The post shows at the top of Home and of the channel feed without refreshing, and in the classic chat.
- [ ] Card ⋮: copy link, edit caption, pin/unpin, statistics (big channels), delete (confirmation), report (other people's posts).

## 5. Feeds (★)
- [ ] Home loads on a cold start; tapping the Home tab again at the top reloads.
- [ ] Video card: autoplays muted only when autoplay settings allow (Settings > Data > autoplay); tap plays/unmutes; full screen button; no sound after scrolling away or leaving the screen; only one video at a time.
- [ ] Long mixed feed (photos, albums, videos, polls, files, voice, stickers, forwarded posts): smooth scrolling, unsupported posts show a "View in chat" card, nothing blank.
- [ ] Caption: more/less, links open, @mentions and #hashtags open the right screens.
- [ ] Hearts: like/unlike, double-tap, long-press reaction bar under the finger; channel with reactions off shows the message; reaction limit reached rolls back.
- [ ] Comments button opens the discussion thread (channel with discussion group) or the post in chat.
- [ ] Channel not joined (from search): Join button works; unread badge of a joined channel clears after opening its feed.

## 6. Reels (★)
- [ ] 100 swipes: no crash, memory stable (Android Studio profiler or `adb shell dumpsys meminfo com.tigi.app`), one sound at a time.
- [ ] Tap pause/play, double-tap heart, long-press reactions, mute toggle remembered after restart.
- [ ] Headphones unplugged: playback pauses. Incoming call: playback stops and resumes cleanly after.
- [ ] Airplane mode with an empty list: error with "Try again".

## 7. Profile (★)
- [ ] Channel avatar/name from a card opens the Tigi profile: counts, grid, reels tab, tile opens the feed at that post, Info opens Telegram's channel info (notifications, members, admin tools).
- [ ] A person with a personal channel: Tigi profile with Message and Info; a person without: Telegram's profile.
- [ ] Deleted, restricted, private (not joined) channels: no crash, clear message.

## 8. Live
- [ ] Create sheet > Go live in a channel you manage: Telegram's start dialog, camera on in the call, viewers can join from the LIVE badge, viewer count shows, end with confirmation.
- [ ] Deny camera/mic permission: clear message, nothing stuck. Network drop during live: reconnect or clean exit.

## 9. Accessibility, layout, performance
- [ ] TalkBack: every new button announces a label (like, comments, share, more, mute, full screen, join, live, tabs).
- [ ] Font scale 200%: no clipped tab labels, buttons or counts; bold type (weight 650) not clipped.
- [ ] Tablet: channels open as chats in the right pane; feed menu item opens the feed.
- [ ] Cold start time not slower than the previous build (`adb shell am start -W com.tigi.app/org.telegram.ui.LaunchActivity`, 5 runs, compare).
