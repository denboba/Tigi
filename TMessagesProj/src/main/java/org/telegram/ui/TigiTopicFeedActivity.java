package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * Nilak: one card feed, several ways to slice the channels the user follows.
 * <ul>
 * <li>BRIEF: the last day's posts, reposts merged, the 20 most worth reading first (views and how often the user
 * opens the channel), "Mark all as read" at the end.</li>
 * <li>TEXT: text-only posts, with topic chips (News, Politics, Markets, Sport, Tech...) matched by keywords.</li>
 * <li>READ: articles: Telegraph / Instant View links and long posts.</li>
 * <li>FRIENDS: posts from the personal channels of people who are mutual contacts.</li>
 * </ul>
 * Everything runs on the phone from channels.getHistory; nothing needs a server.
 */
public class TigiTopicFeedActivity extends TigiFeedBase {

    public static final int MODE_BRIEF = 0;
    public static final int MODE_TEXT = 1;
    public static final int MODE_READ = 2;
    public static final int MODE_FRIENDS = 3;

    private static final int MAX_CHANNELS = 30;
    private static final int PER_CHANNEL = 20;
    private static final int PARALLEL = 3;
    private static final int BRIEF_SIZE = 20;
    private static final int MAX_POSTS = 80;
    private static final int MAX_FRIENDS = 40;

    private static final int[] TOPICS = {
        R.string.NilakTopicAll, R.string.NilakInterestNews, R.string.NilakTopicPolitics, R.string.NilakTopicMarkets,
        R.string.NilakInterestSports, R.string.NilakInterestTech, R.string.NilakInterestFaith, R.string.NilakInterestJobs
    };
    /** Lower-case words (English and Amharic) that put a post or channel in a topic; index matches TOPICS. */
    private static final String[][] TOPIC_WORDS = {
        {},
        {"news", "breaking", "report", "ዜና", "መረጃ", "አስቸኳይ"},
        {"politic", "government", "election", "parliament", "minister", "party", "ፖለቲካ", "መንግስት", "መንግሥት", "ምርጫ", "ፓርላማ", "ሚኒስትር", "ፓርቲ"},
        {"market", "price", "birr", "dollar", "usd", "exchange", "rate", "stock", "crypto", "bitcoin", "gold", "ዋጋ", "ብር", "ዶላር", "ገበያ", "ምንዛሬ", "ወርቅ"},
        {"football", "sport", "match", "goal", "league", "premier", "ስፖርት", "እግር ኳስ", "ጨዋታ", "ሊግ", "ጎል"},
        {"tech", " ai ", "software", "startup", "developer", "android", "iphone", "ቴክ", "ቴክኖሎጂ", "ሶፍትዌር"},
        {"church", "orthodox", "mosque", "islam", "bible", "quran", "faith", "ቤተ ክርስቲያን", "ኦርቶዶክስ", "መስጊድ", "እምነት", "መንፈሳዊ"},
        {"job", "vacancy", "hiring", "career", "intern", "ሥራ", "ስራ", "ቅጥር", "ማስታወቂያ"}
    };

    private int mode;
    private int topic;
    private boolean loading;
    private int generation;
    private final ArrayList<FeedPost> all = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> loadedChannels = new ArrayList<>();
    private int unreadTotal;

    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout chipsRow;
    private TextView markReadButton;
    private HorizontalScrollView marketScroll;
    private LinearLayout marketStrip;

    private static final int TOPIC_MARKETS = 3;
    /** Rates people check: label, pattern (the number is group 1). Read from channel posts, not an official source. */
    private static final int[] MARKET_LABELS = {R.string.NilakRateUsd, R.string.NilakRateEur, R.string.NilakRateGold, R.string.NilakRateFuel};
    private static final java.util.regex.Pattern[] MARKET_PATTERNS = {
        java.util.regex.Pattern.compile("(?i)(?:usd|us dollar|dollar|ዶላር)[^\\d\\n]{0,24}(\\d{1,6}(?:[.,]\\d{1,4})?)"),
        java.util.regex.Pattern.compile("(?i)(?:eur|euro|ዩሮ)[^\\d\\n]{0,24}(\\d{1,6}(?:[.,]\\d{1,4})?)"),
        java.util.regex.Pattern.compile("(?i)(?:gold|ወርቅ)[^\\d\\n]{0,24}(\\d{1,3}(?:[,]\\d{3})*(?:[.]\\d{1,2})?)"),
        java.util.regex.Pattern.compile("(?i)(?:benzine|benzene|petrol|gasoline|fuel|ቤንዚን|ነዳጅ)[^\\d\\n]{0,24}(\\d{1,4}(?:[.,]\\d{1,2})?)")
    };

    public static TigiTopicFeedActivity of(int mode) {
        final Bundle args = new Bundle();
        args.putInt("mode", mode);
        return new TigiTopicFeedActivity(args);
    }

    public TigiTopicFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        mode = arguments != null ? arguments.getInt("mode", MODE_BRIEF) : MODE_BRIEF;
        if (mode == MODE_BRIEF) {
            org.telegram.messenger.TigiBriefReminder.markOpenedToday();
        }
        return super.onFragmentCreate();
    }

    @Override
    protected boolean appliesFeedFilter() {
        return true;
    }

    @Override
    protected boolean isLoadingPosts() {
        return loading;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        load();
        return view;
    }

    private String title() {
        switch (mode) {
            case MODE_TEXT: return LocaleController.getString(R.string.NilakText);
            case MODE_READ: return LocaleController.getString(R.string.NilakRead);
            case MODE_FRIENDS: return LocaleController.getString(R.string.NilakFriends);
            default: return LocaleController.getString(R.string.NilakBrief);
        }
    }

    @Override
    protected View createHeaderView(Context context) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        applyTopInset(header);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        row.addView(back, LayoutHelper.createLinear(48, 56));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setText(title());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(TigiStyle.displayExtraBold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
        texts.addView(titleView);
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setVisibility(View.GONE);
        texts.addView(subtitleView);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));

        if (mode == MODE_BRIEF) {
            final ImageView bell = new ImageView(context);
            bell.setScaleType(ImageView.ScaleType.CENTER);
            bell.setImageResource(R.drawable.tigi_bell_24);
            bell.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bell.setContentDescription(LocaleController.getString(R.string.NilakBriefReminder));
            bell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            bell.setOnClickListener(v -> pickReminder());
            row.addView(bell, LayoutHelper.createLinear(44, 48));
            markReadButton = new TextView(context);
            markReadButton.setText(LocaleController.getString(R.string.NilakMarkAllRead));
            markReadButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            markReadButton.setTypeface(TigiStyle.displayBold());
            markReadButton.setTextColor(TigiStyle.accent());
            markReadButton.setGravity(Gravity.CENTER);
            markReadButton.setPadding(dp(12), 0, dp(12), 0);
            markReadButton.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
            markReadButton.setOnClickListener(v -> markAllRead());
            markReadButton.setVisibility(View.GONE);
            row.addView(markReadButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 12, 0));
        } else if (mode == MODE_TEXT || mode == MODE_READ) {
            final ImageView write = new ImageView(context);
            write.setScaleType(ImageView.ScaleType.CENTER);
            write.setImageResource(R.drawable.msg_edit);
            write.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            write.setContentDescription(LocaleController.getString(mode == MODE_READ ? R.string.NilakWriteArticle : R.string.NilakWritePost));
            write.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            write.setOnClickListener(v -> {
                if (mode == MODE_READ) {
                    presentFragment(new TigiArticleActivity());
                } else {
                    TigiCreateSheet.show(this);
                }
            });
            row.addView(write, LayoutHelper.createLinear(48, 48, 0, 0, 4, 0));
        }
        header.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        if (mode == MODE_TEXT) {
            final HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);
            chipsRow.setPadding(dp(12), 0, dp(12), dp(8));
            for (int i = 0; i < TOPICS.length; i++) {
                final int index = i;
                final TextView chip = new TextView(context);
                chip.setText(LocaleController.getString(TOPICS[i]));
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setTypeface(TigiStyle.displayBold());
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), 0, dp(14), 0);
                chip.setOnClickListener(v -> {
                    topic = index;
                    bindChips();
                    applyTopic();
                });
                chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
            }
            scroll.addView(chipsRow);
            header.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            bindChips();
            marketScroll = new HorizontalScrollView(context);
            marketScroll.setHorizontalScrollBarEnabled(false);
            marketStrip = new LinearLayout(context);
            marketStrip.setOrientation(LinearLayout.HORIZONTAL);
            marketStrip.setPadding(dp(12), 0, dp(12), dp(10));
            marketScroll.addView(marketStrip);
            marketScroll.setVisibility(View.GONE);
            header.addView(marketScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return header;
    }

    private void bindChips() {
        if (chipsRow == null) {
            return;
        }
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            final TextView chip = (TextView) chipsRow.getChildAt(i);
            final boolean on = i == topic;
            chip.setSelected(on);
            chip.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chip.setBackground(on ? TigiStyle.primaryPill(17) : Theme.createRoundRectDrawable(dp(17), Theme.getColor(Theme.key_windowBackgroundGray)));
        }
    }

    @Override
    protected void updateEmptyView() {
        super.updateEmptyView();
        if (emptyView != null && posts.isEmpty() && !loading) {
            switch (mode) {
                case MODE_BRIEF: emptyView.setText(LocaleController.getString(R.string.NilakBriefEmpty)); break;
                case MODE_READ: emptyView.setText(LocaleController.getString(R.string.NilakReadEmpty)); break;
                case MODE_FRIENDS: emptyView.setText(LocaleController.getString(R.string.NilakFriendsEmpty)); break;
                default: emptyView.setText(LocaleController.getString(R.string.NilakTextEmpty)); break;
            }
        }
    }

    /* loading */

    private void load() {
        if (loading) {
            return;
        }
        if (mode == MODE_FRIENDS) {
            loadFriendChannels();
            return;
        }
        final ArrayList<TLRPC.Chat> channels = TigiPosting.followedChannels(currentAccount);
        if (mode == MODE_BRIEF) {
            // the channels with unread posts first
            unreadTotal = 0;
            final HashMap<Long, Integer> unread = new HashMap<>();
            for (TLRPC.Chat chat : channels) {
                final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(-chat.id);
                final int count = dialog != null ? dialog.unread_count : 0;
                unread.put(chat.id, count);
                unreadTotal += count;
            }
            Collections.sort(channels, (a, b) -> unread.get(b.id) - unread.get(a.id));
        } else {
            TigiFeedPrefs.sortByAffinity(currentAccount, channels);
        }
        if (channels.size() >= 5 || mode == MODE_BRIEF) {
            loadPosts(new ArrayList<>(channels.subList(0, Math.min(MAX_CHANNELS, channels.size()))));
            return;
        }
        // few channels: fill with recommended ones so the page is not empty
        loading = true;
        updateEmptyView();
        final int gen = ++generation;
        TigiPosting.loadRecommendedChannels(currentAccount, new ArrayList<>(channels), recommended -> {
            if (gen != generation || isDead()) {
                return;
            }
            loading = false;
            final ArrayList<TLRPC.Chat> merged = new ArrayList<>(channels);
            for (int i = 0; i < recommended.size() && merged.size() < MAX_CHANNELS; i++) {
                merged.add(recommended.get(i));
            }
            loadPosts(merged);
        });
    }

    /** Mutual contacts who have a personal channel; asks for up to 40 profiles, one at a time. */
    private void loadFriendChannels() {
        loading = true;
        updateEmptyView();
        final int gen = ++generation;
        final ArrayList<TLRPC.User> friends = new ArrayList<>();
        final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>(ContactsController.getInstance(currentAccount).contacts);
        for (int i = 0; i < contacts.size() && friends.size() < MAX_FRIENDS; i++) {
            final TLRPC.User user = getMessagesController().getUser(contacts.get(i).user_id);
            if (user != null && user.mutual_contact && !user.bot && !user.deleted && !user.self) {
                friends.add(user);
            }
        }
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
        final int[] next = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (gen != generation || isDead()) {
                return;
            }
            while (next[0] < friends.size()) {
                final TLRPC.User user = friends.get(next[0]);
                final TLRPC.UserFull full = getMessagesController().getUserFull(user.id);
                if (full != null) {
                    next[0]++;
                    addPersonalChannel(full, channels);
                    continue;
                }
                next[0]++;
                getMessagesController().loadFullUser(user, classGuid, true, loaded -> AndroidUtilities.runOnUIThread(() -> {
                    final TLRPC.UserFull got = getMessagesController().getUserFull(user.id);
                    if (got != null) {
                        addPersonalChannel(got, channels);
                    }
                    step[0].run();
                }));
                return;
            }
            loading = false;
            if (channels.isEmpty()) {
                finish(new ArrayList<>());
            } else {
                loadPosts(channels);
            }
        };
        step[0].run();
    }

    private void addPersonalChannel(TLRPC.UserFull full, ArrayList<TLRPC.Chat> channels) {
        if (full.personal_channel_id == 0) {
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(full.personal_channel_id);
        if (chat != null && !chat.restricted && !channels.contains(chat)) {
            channels.add(chat);
        }
    }

    private void loadPosts(ArrayList<TLRPC.Chat> channels) {
        loading = true;
        updateEmptyView();
        loadedChannels.clear();
        loadedChannels.addAll(channels);
        final int gen = ++generation;
        final ArrayList<FeedPost> result = new ArrayList<>();
        if (channels.isEmpty()) {
            finish(result);
            return;
        }
        final int[] next = {0};
        final int[] pending = {channels.size()};
        final Runnable[] startNext = new Runnable[1];
        startNext[0] = () -> {
            if (gen != generation || isDead() || next[0] >= channels.size()) {
                return;
            }
            final TLRPC.Chat chat = channels.get(next[0]++);
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(-chat.id);
            req.limit = PER_CHANNEL;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    result.addAll(buildPosts(chat, res.messages));
                } else if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                    pending[0] -= channels.size() - next[0];
                    next[0] = channels.size();
                }
                pending[0]--;
                if (pending[0] <= 0) {
                    finish(result);
                } else {
                    startNext[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL; i++) {
            startNext[0].run();
        }
    }

    private void finish(ArrayList<FeedPost> result) {
        loading = false;
        if (isDead()) {
            return;
        }
        final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
        final HashSet<String> seen = new HashSet<>();
        final int dayAgo = getConnectionsManager().getCurrentTime() - 24 * 60 * 60;
        final ArrayList<FeedPost> kept = new ArrayList<>();
        for (FeedPost post : result) {
            if (post.chat == null || post.messages.isEmpty() || post.sponsored || filter.skips(post.chat.id, post.primary().getId())) {
                continue;
            }
            if (!fits(post, dayAgo)) {
                continue;
            }
            // the same post reposted in several channels shows once
            if (!seen.add(dedupeKey(post))) {
                continue;
            }
            kept.add(post);
        }
        if (mode == MODE_BRIEF) {
            Collections.sort(kept, (a, b) -> Double.compare(score(b), score(a)));
            all.clear();
            all.addAll(kept.size() > BRIEF_SIZE ? kept.subList(0, BRIEF_SIZE) : kept);
            if (subtitleView != null) {
                subtitleView.setText(LocaleController.formatString(R.string.NilakBriefSubtitle, Math.max(unreadTotal, kept.size()), all.size()));
                subtitleView.setVisibility(View.VISIBLE);
            }
            if (markReadButton != null) {
                markReadButton.setVisibility(unreadTotal > 0 ? View.VISIBLE : View.GONE);
            }
        } else {
            Collections.sort(kept, (a, b) -> b.date - a.date);
            all.clear();
            all.addAll(kept.size() > MAX_POSTS ? kept.subList(0, MAX_POSTS) : kept);
        }
        prefetchOnWifi(all);
        applyTopic();
    }

    private boolean fits(FeedPost post, int dayAgo) {
        switch (mode) {
            case MODE_BRIEF:
                return post.date >= dayAgo;
            case MODE_TEXT:
                return post.media.isEmpty() && TextUtils.isEmpty(post.fallback) && post.text != null && post.text.length() >= 40;
            case MODE_READ:
                return isArticle(post);
            default:
                return true;
        }
    }

    /** A Telegraph / Instant View link, or a long written post. */
    static boolean isArticle(FeedPost post) {
        for (MessageObject message : post.messages) {
            final TLRPC.MessageMedia media = message.messageOwner.media;
            if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
                final TLRPC.WebPage page = media.webpage;
                if (page.cached_page != null || "telegraph".equals(page.site_name != null ? page.site_name.toLowerCase(Locale.US) : null)
                    || page.url != null && page.url.contains("telegra.ph/")) {
                    return true;
                }
            }
            final String text = message.messageOwner.message;
            if (text != null && (text.contains("telegra.ph/") || text.length() >= 900)) {
                return true;
            }
        }
        return false;
    }

    private static String dedupeKey(FeedPost post) {
        final MessageObject first = post.primary();
        if (first.getDocument() != null) {
            return "d" + first.getDocument().id;
        }
        final TLRPC.MessageMedia media = first.messageOwner.media;
        if (media != null && media.photo != null) {
            return "p" + media.photo.id;
        }
        final String text = post.text != null ? post.text.toString().trim().toLowerCase(Locale.ROOT) : "";
        return "t" + (text.length() > 80 ? text.substring(0, 80) : text) + (text.isEmpty() ? post.chat.id + ":" + first.getId() : "");
    }

    /** Brief ranking: views (log scale) lifted by how often the user opens the channel, newer a little higher. */
    private double score(FeedPost post) {
        final int views = Math.max(post.primary().messageOwner.views, 1);
        final int affinity = TigiFeedPrefs.affinityOf(currentAccount, post.chat.id);
        final double hoursOld = Math.max(0, (getConnectionsManager().getCurrentTime() - post.date) / 3600.0);
        return Math.log10(views + 10) * (1 + 0.15 * Math.min(affinity, 20)) - hoursOld * 0.03;
    }

    /** The newest value each market pattern finds in the loaded posts, as tiles; tap opens the post it came from. */
    private void bindMarkets() {
        if (marketScroll == null) {
            return;
        }
        marketStrip.removeAllViews();
        if (topic != TOPIC_MARKETS) {
            marketScroll.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < MARKET_PATTERNS.length; i++) {
            FeedPost source = null;
            String value = null;
            for (FeedPost post : all) {
                if (post.text == null) {
                    continue;
                }
                final java.util.regex.Matcher matcher = MARKET_PATTERNS[i].matcher(post.text);
                while (matcher.find()) {
                    final String number = matcher.group(1);
                    // a bare year or a phone-number fragment is not a price
                    if (number.matches("(19|20)\\d\\d") || number.replaceAll("[^0-9]", "").length() > 8) {
                        continue;
                    }
                    if (source == null || post.date > source.date) {
                        source = post;
                        value = number;
                    }
                    break;
                }
            }
            if (source != null) {
                marketStrip.addView(marketTile(MARKET_LABELS[i], value, source), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
            }
        }
        marketScroll.setVisibility(marketStrip.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private View marketTile(int label, String value, FeedPost source) {
        final Context context = getParentActivity();
        final LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(12), dp(8), dp(14), dp(8));
        tile.setMinimumWidth(dp(120));
        tile.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
        final TextView name = new TextView(context);
        name.setText(LocaleController.getString(label));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        name.setTypeface(TigiStyle.displayBold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        tile.addView(name);
        final TextView number = new TextView(context);
        number.setText(value);
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        number.setTypeface(TigiStyle.displayExtraBold());
        number.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tile.addView(number);
        final TextView from = new TextView(context);
        from.setText(source.chat.title + " · " + LocaleController.stringForMessageListDate(source.date));
        from.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        from.setMaxWidth(dp(150));
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        from.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        tile.addView(from);
        tile.setContentDescription(LocaleController.getString(label) + " " + value + ", " + source.chat.title);
        tile.setOnClickListener(v -> {
            final Bundle args = new Bundle();
            args.putLong("chat_id", source.chat.id);
            args.putInt("start_message_id", source.primary().getId());
            presentFragment(new ChannelFeedActivity(args));
        });
        return tile;
    }

    private void applyTopic() {
        bindMarkets();
        posts.clear();
        if (mode != MODE_TEXT || topic == 0) {
            posts.addAll(all);
        } else {
            for (FeedPost post : all) {
                if (inTopic(post, topic)) {
                    posts.add(post);
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateEmptyView();
    }

    private static boolean inTopic(FeedPost post, int topic) {
        final String haystack = (" " + (post.chat != null ? post.chat.title : "") + " " + (post.text != null ? post.text : "") + " ").toLowerCase(Locale.ROOT);
        for (String word : TOPIC_WORDS[topic]) {
            if (haystack.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static final int[] REMINDER_HOURS = {7, 8, 9, 12, 18, 21};

    /** Off, or one of a few hours; the current choice is marked. */
    private void pickReminder() {
        if (getParentActivity() == null) {
            return;
        }
        final boolean on = org.telegram.messenger.TigiBriefReminder.isEnabled();
        final int hour = org.telegram.messenger.TigiBriefReminder.hour();
        final CharSequence[] items = new CharSequence[REMINDER_HOURS.length + 1];
        items[0] = (!on ? "✓  " : "") + LocaleController.getString(R.string.NilakReminderOff);
        for (int i = 0; i < REMINDER_HOURS.length; i++) {
            final java.util.Calendar time = java.util.Calendar.getInstance();
            time.set(java.util.Calendar.HOUR_OF_DAY, REMINDER_HOURS[i]);
            time.set(java.util.Calendar.MINUTE, 0);
            final String label = LocaleController.getInstance().getFormatterDay().format(time.getTime());
            items[i + 1] = (on && hour == REMINDER_HOURS[i] ? "✓  " : "") + label;
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakBriefReminder));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                org.telegram.messenger.TigiBriefReminder.setEnabled(false);
            } else {
                org.telegram.messenger.TigiBriefReminder.setHour(REMINDER_HOURS[which - 1]);
                org.telegram.messenger.TigiBriefReminder.setEnabled(true);
            }
        });
        showDialog(builder.create());
    }

    private void markAllRead() {
        for (TLRPC.Chat chat : loadedChannels) {
            final TLRPC.Dialog dialog = getMessagesController().dialogs_dict.get(-chat.id);
            if (dialog != null && dialog.unread_count > 0) {
                getMessagesController().markDialogAsRead(-chat.id, dialog.top_message, dialog.top_message, 0, false, 0, 0, true, 0);
            }
        }
        unreadTotal = 0;
        if (markReadButton != null) {
            markReadButton.setVisibility(View.GONE);
        }
        org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.NilakCaughtUp)).show();
    }

    @Override
    protected void onFeedFilterUndone() {
        load();
    }
}
