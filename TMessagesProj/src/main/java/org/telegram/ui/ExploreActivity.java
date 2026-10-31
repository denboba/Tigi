package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nilak Explore: an endless grid of photos and videos from channels the user does not follow yet ("For you": channels
 * Telegram recommends next to the ones they follow), the hashtags trending in that grid as chips, posts for a hashtag
 * (channels.searchPosts) and search for channels and people by name. Full-text post search is not used: Telegram
 * limits it per user and may require Premium.
 */
public class ExploreActivity extends BaseFragment {

    private static final int MODE_FOR_YOU = 0;
    private static final int MODE_HASHTAG = 1;
    private static final int MODE_SEARCH = 2;

    private static final int PER_CHANNEL = 12;
    private static final int CHANNELS_PER_ROUND = 4;
    private static final int PARALLEL = 3;
    private static final int MAX_TAG_CHIPS = 8;
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,40})");

    private static class Cursor {
        final TLRPC.Chat chat;
        int offsetId;
        boolean done;

        Cursor(TLRPC.Chat chat) {
            this.chat = chat;
        }
    }

    private int mode = MODE_FOR_YOU;
    private String hashtag;
    private String query = "";

    // For you
    private final ArrayList<Cursor> cursors = new ArrayList<>();
    private boolean channelsLoaded;
    private int roundStart;
    private final ArrayList<MessageObject> forYou = new ArrayList<>();
    private final HashMap<String, Integer> tagCounts = new HashMap<>();
    private boolean forYouEnd;

    // hashtag
    private final ArrayList<MessageObject> tagged = new ArrayList<>();
    private int nextRate;
    private TLRPC.InputPeer offsetPeer;
    private int offsetId;
    private boolean taggedEnd;

    // search
    private final ArrayList<TLObject> found = new ArrayList<>();
    private boolean searching;
    private Runnable searchRunnable;

    private final HashSet<String> seen = new HashSet<>();
    private boolean loading;
    private String errorText;
    private int generation;

    private RecyclerListView listView;
    private GridLayoutManager layoutManager;
    private Adapter adapter;
    private EditTextBoldCursor searchField;
    private LinearLayout chipsRow;
    private final ArrayList<TextView> chipViews = new ArrayList<>();

    public ExploreActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        hashtag = arguments != null ? arguments.getString("hashtag") : null;
        if (!TextUtils.isEmpty(hashtag)) {
            mode = MODE_HASHTAG;
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        generation++;
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
            searchRunnable = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // back from a post: drop what the user hid there
        if (adapter != null && mode != MODE_SEARCH) {
            final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
            final ArrayList<MessageObject> tiles = mode == MODE_HASHTAG ? tagged : forYou;
            boolean changed = false;
            for (int i = tiles.size() - 1; i >= 0; i--) {
                final MessageObject m = tiles.get(i);
                if (filter.skips(-m.getDialogId(), m.getId())) {
                    tiles.remove(i);
                    changed = true;
                }
            }
            if (changed) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    private boolean isDead() {
        return isFinishing() || fragmentView == null || getParentActivity() == null;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createTopBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listView = new RecyclerListView(context);
        layoutManager = new GridLayoutManager(context, 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == Adapter.TYPE_TILE ? 1 : 3;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + dp(16));
        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onItemClick(position));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    AndroidUtilities.hideKeyboard(searchField);
                }
                if (layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 12) {
                    loadMore();
                }
            }
        });
        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;

        if (mode == MODE_HASHTAG) {
            searchField.setText("#" + hashtag);
            searchField.setSelection(searchField.length());
        }
        loadMore();
        return fragmentView;
    }

    private View createTopBar(Context context) {
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        ViewCompat.setOnApplyWindowInsetsListener(bar, (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, Math.max(bars.top, 0), 0, 0);
            return insets;
        });

        final LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        searchRow.addView(back, LayoutHelper.createLinear(48, 48, 4, 0, 0, 0));

        final FrameLayout field = new FrameLayout(context);
        field.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.outline_header_search);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
        field.addView(icon, LayoutHelper.createFrame(36, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 4, 0, 4, 0));
        searchField = new EditTextBoldCursor(context);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        searchField.setHint(LocaleController.getString(R.string.NilakExploreSearchHint));
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setCursorColor(TigiStyle.accent());
        searchField.setCursorWidth(1.5f);
        searchField.setPadding(0, 0, 0, 0);
        searchField.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryChanged(s.toString());
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                AndroidUtilities.hideKeyboard(searchField);
                return true;
            }
            return false;
        });
        field.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 12 : 42, 0, LocaleController.isRTL ? 42 : 12, 0));
        searchRow.addView(field, LayoutHelper.createLinear(0, 40, 1f, 0, 0, 12, 0));
        bar.addView(searchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setPadding(dp(12), 0, dp(12), dp(8));
        scroll.addView(chipsRow, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rebuildChips();
        return bar;
    }

    /* chips: For you + the hashtags trending in the grid */

    private void rebuildChips() {
        if (chipsRow == null) {
            return;
        }
        chipsRow.removeAllViews();
        chipViews.clear();
        addChip(LocaleController.getString(R.string.TigiReelsForYou), null);
        // the other ways in: each opens its own screen
        addChip(LocaleController.getString(R.string.NilakApps), "@apps");
        addChip(LocaleController.getString(R.string.NilakText), "@text");
        addChip(LocaleController.getString(R.string.NilakRead), "@read");
        addChip(LocaleController.getString(R.string.NilakFriends), "@friends");
        addChip(LocaleController.getString(R.string.NilakBrief), "@brief");
        final ArrayList<String> tags = new ArrayList<>(tagCounts.keySet());
        Collections.sort(tags, (a, b) -> tagCounts.get(b) - tagCounts.get(a));
        if (!TextUtils.isEmpty(hashtag) && !tags.contains(hashtag)) {
            tags.add(0, hashtag);
        }
        for (int i = 0; i < tags.size() && i < MAX_TAG_CHIPS; i++) {
            addChip("#" + tags.get(i), tags.get(i));
        }
        updateChips();
    }

    private void addChip(String text, String tag) {
        final TextView chip = new TextView(getParentActivity());
        chip.setText(text);
        chip.setTag(tag);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setTypeface(TigiStyle.displayBold());
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setOnClickListener(v -> {
            if (tag != null && tag.startsWith("@")) {
                openHub(tag);
                return;
            }
            if (tag == null) {
                searchField.setText("");
            } else {
                searchField.setText("#" + tag);
                searchField.setSelection(searchField.length());
            }
            AndroidUtilities.hideKeyboard(searchField);
        });
        chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        chipViews.add(chip);
    }

    private void openHub(String tag) {
        AndroidUtilities.hideKeyboard(searchField);
        switch (tag) {
            case "@apps": TigiAppsActivity.open(this); break;
            case "@text": presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_TEXT)); break;
            case "@read": presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_READ)); break;
            case "@friends": presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_FRIENDS)); break;
            case "@brief": presentFragment(TigiTopicFeedActivity.of(TigiTopicFeedActivity.MODE_BRIEF)); break;
        }
    }

    private void updateChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            final TextView chip = chipViews.get(i);
            final String tag = (String) chip.getTag();
            if (tag != null && tag.startsWith("@")) {
                chip.setTextColor(TigiStyle.accent());
                chip.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
                chip.setSelected(false);
                continue;
            }
            final boolean on = tag == null ? mode == MODE_FOR_YOU : mode == MODE_HASHTAG && tag.equalsIgnoreCase(hashtag);
            chip.setTextColor(on ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chip.setBackground(on ? TigiStyle.primaryPill(16) : Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundGray)));
            chip.setSelected(on);
        }
    }

    /* query */

    private void onQueryChanged(String text) {
        final String trimmed = text.trim();
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
            searchRunnable = null;
        }
        if (trimmed.isEmpty()) {
            setMode(MODE_FOR_YOU, null);
        } else if (trimmed.startsWith("#") && trimmed.length() > 2) {
            final String tag = trimmed.substring(1).replaceAll("\\s.*$", "");
            searchRunnable = () -> setMode(MODE_HASHTAG, tag);
            AndroidUtilities.runOnUIThread(searchRunnable, 450);
        } else if (!trimmed.startsWith("#") && trimmed.length() >= 2) {
            query = trimmed;
            searchRunnable = () -> setMode(MODE_SEARCH, null);
            AndroidUtilities.runOnUIThread(searchRunnable, 400);
        }
    }

    private void setMode(int newMode, String tag) {
        if (newMode == mode && (newMode != MODE_HASHTAG || TextUtils.equals(tag, hashtag)) && newMode != MODE_SEARCH) {
            return;
        }
        generation++;
        loading = false;
        errorText = null;
        mode = newMode;
        if (newMode == MODE_HASHTAG) {
            hashtag = tag;
            tagged.clear();
            nextRate = 0;
            offsetPeer = null;
            offsetId = 0;
            taggedEnd = false;
        } else if (newMode == MODE_SEARCH) {
            found.clear();
        }
        rebuildChips();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (listView != null) {
            listView.scrollToPosition(0);
        }
        loadMore();
    }

    /* loading */

    private void loadMore() {
        if (loading || isDead()) {
            return;
        }
        if (mode == MODE_FOR_YOU) {
            loadForYou();
        } else if (mode == MODE_HASHTAG) {
            loadHashtag();
        } else {
            loadSearch();
        }
    }

    private void loadForYou() {
        if (forYouEnd) {
            return;
        }
        final int gen = generation;
        if (!channelsLoaded) {
            loading = true;
            adapter.notifyDataSetChanged();
            final ArrayList<TLRPC.Chat> followed = TigiPosting.followedChannels(currentAccount);
            TigiPosting.loadRecommendedChannels(currentAccount, new ArrayList<>(followed.subList(0, Math.min(5, followed.size()))), recommended -> {
                if (gen != generation || isDead()) {
                    return;
                }
                loading = false;
                channelsLoaded = true;
                final TigiFeedPrefs.Filter filter = TigiFeedPrefs.filter(currentAccount);
                final ArrayList<TLRPC.Chat> ordered = new ArrayList<>();
                for (int i = 0; i < recommended.size(); i++) {
                    if (!filter.hidesChannel(recommended.get(i).id)) {
                        ordered.add(recommended.get(i));
                    }
                }
                // channels the user tends to open first (on-device only)
                TigiFeedPrefs.sortByAffinity(currentAccount, ordered);
                for (int i = 0; i < ordered.size(); i++) {
                    cursors.add(new Cursor(ordered.get(i)));
                }
                if (cursors.isEmpty()) {
                    forYouEnd = true;
                    adapter.notifyDataSetChanged();
                } else {
                    loadForYou();
                }
            });
            return;
        }
        final ArrayList<Cursor> round = new ArrayList<>();
        for (int i = 0; i < cursors.size() && round.size() < CHANNELS_PER_ROUND; i++) {
            final Cursor cursor = cursors.get((roundStart + i) % cursors.size());
            if (!cursor.done) {
                round.add(cursor);
            }
        }
        roundStart = (roundStart + CHANNELS_PER_ROUND) % Math.max(1, cursors.size());
        if (round.isEmpty()) {
            forYouEnd = true;
            adapter.notifyDataSetChanged();
            return;
        }
        loading = true;
        if (forYou.isEmpty()) {
            adapter.notifyDataSetChanged();
        }
        final ArrayList<ArrayList<MessageObject>> perChannel = new ArrayList<>();
        final int[] next = {0};
        final int[] pending = {round.size()};
        final int[] failures = {0};
        final Runnable[] start = new Runnable[1];
        start[0] = () -> {
            if (gen != generation || next[0] >= round.size()) {
                return;
            }
            final Cursor cursor = round.get(next[0]++);
            final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = getMessagesController().getInputPeer(-cursor.chat.id);
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterPhotoVideo();
            req.limit = PER_CHANNEL;
            req.offset_id = cursor.offsetId;
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    getMessagesController().putUsers(res.users, false);
                    getMessagesController().putChats(res.chats, false);
                    final ArrayList<MessageObject> list = new ArrayList<>();
                    int minId = cursor.offsetId;
                    for (int i = 0; i < res.messages.size(); i++) {
                        final TLRPC.Message message = res.messages.get(i);
                        if (message.id > 0 && (minId == 0 || message.id < minId)) {
                            minId = message.id;
                        }
                        final MessageObject object = toTile(message, cursor.chat.id);
                        if (object != null) {
                            list.add(object);
                            countTags(object);
                        }
                    }
                    cursor.done = res.messages.isEmpty() || minId == cursor.offsetId;
                    cursor.offsetId = minId;
                    perChannel.add(list);
                } else {
                    failures[0]++;
                    cursor.done = true;
                    if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT")) {
                        pending[0] -= round.size() - next[0];
                        next[0] = round.size();
                    }
                }
                if (--pending[0] <= 0) {
                    finishForYouRound(perChannel, failures[0] == round.size());
                } else {
                    start[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL; i++) {
            start[0].run();
        }
    }

    private void finishForYouRound(ArrayList<ArrayList<MessageObject>> perChannel, boolean allFailed) {
        loading = false;
        if (isDead()) {
            return;
        }
        if (allFailed && forYou.isEmpty()) {
            errorText = LocaleController.getString(R.string.TigiFeedError);
        }
        // one from each channel in turn, so the grid mixes channels
        final int start = forYou.size();
        boolean added = true;
        for (int round = 0; added; round++) {
            added = false;
            for (int c = 0; c < perChannel.size(); c++) {
                if (round < perChannel.get(c).size()) {
                    forYou.add(perChannel.get(c).get(round));
                    added = true;
                }
            }
        }
        final boolean firstRound = start == 0;
        if (firstRound) {
            rebuildChips();
            adapter.notifyDataSetChanged();
        } else if (forYou.size() > start) {
            adapter.notifyItemRangeInserted(start, forYou.size() - start);
        }
        if (forYou.size() - start < 6 && !allFailed) {
            // little media in these channels: take the next ones right away
            AndroidUtilities.runOnUIThread(this::loadMore);
        }
    }

    private void loadHashtag() {
        if (taggedEnd || TextUtils.isEmpty(hashtag)) {
            return;
        }
        loading = true;
        if (tagged.isEmpty()) {
            adapter.notifyDataSetChanged();
        }
        final int gen = generation;
        final TLRPC.TL_channels_searchPosts req = new TLRPC.TL_channels_searchPosts();
        req.flags |= 1;
        req.hashtag = hashtag;
        req.offset_rate = nextRate;
        req.offset_peer = offsetPeer != null ? offsetPeer : new TLRPC.TL_inputPeerEmpty();
        req.offset_id = offsetId;
        req.limit = 40;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (gen != generation || isDead()) {
                return;
            }
            loading = false;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                final int before = tagged.size();
                for (int i = 0; i < res.messages.size(); i++) {
                    final TLRPC.Message message = res.messages.get(i);
                    final long dialogId = MessageObject.getDialogId(message);
                    final MessageObject object = toTile(message, -dialogId);
                    if (object != null) {
                        tagged.add(object);
                    }
                }
                if (res.messages.isEmpty()) {
                    taggedEnd = true;
                } else {
                    final TLRPC.Message last = res.messages.get(res.messages.size() - 1);
                    offsetPeer = getMessagesController().getInputPeer(MessageObject.getDialogId(last));
                    offsetId = last.id;
                    nextRate = res.next_rate;
                    taggedEnd = !(res instanceof TLRPC.TL_messages_messagesSlice) || res.next_rate == 0 && res.messages.size() < req.limit;
                }
                if (before == 0) {
                    adapter.notifyDataSetChanged();
                } else {
                    adapter.notifyItemRangeInserted(before, tagged.size() - before);
                }
                if (tagged.size() - before < 6 && !taggedEnd) {
                    // mostly text posts: ask for the next page
                    AndroidUtilities.runOnUIThread(this::loadMore);
                }
            } else {
                taggedEnd = true;
                errorText = error != null && "PREMIUM_ACCOUNT_REQUIRED".equals(error.text)
                    ? LocaleController.getString(R.string.NilakExploreTagPremium)
                    : LocaleController.getString(R.string.TigiFeedError);
                adapter.notifyDataSetChanged();
            }
        }));
    }

    private void loadSearch() {
        if (searching || TextUtils.isEmpty(query)) {
            return;
        }
        searching = true;
        loading = true;
        adapter.notifyDataSetChanged();
        final int gen = generation;
        final String q = query;
        final TLRPC.TL_contacts_search req = new TLRPC.TL_contacts_search();
        req.q = q;
        req.limit = 40;
        getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            searching = false;
            loading = false;
            if (gen != generation || isDead()) {
                return;
            }
            found.clear();
            if (response instanceof TLRPC.TL_contacts_found) {
                final TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                getMessagesController().putUsers(res.users, false);
                getMessagesController().putChats(res.chats, false);
                final ArrayList<TLRPC.Peer> peers = new ArrayList<>(res.my_results);
                peers.addAll(res.results);
                final HashSet<Long> added = new HashSet<>();
                // channels first: Explore is about content
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < peers.size(); i++) {
                        final long did = DialogObject.getPeerDialogId(peers.get(i));
                        final TLObject object = getMessagesController().getUserOrChat(did);
                        final boolean channel = object instanceof TLRPC.Chat && ChatObject.isChannelAndNotMegaGroup((TLRPC.Chat) object);
                        if (object != null && (pass == 0) == channel && added.add(did)) {
                            found.add(object);
                        }
                    }
                }
            } else {
                errorText = LocaleController.getString(R.string.TigiFeedError);
            }
            adapter.notifyDataSetChanged();
        }));
    }

    /** A grid tile for a message with a photo or video, once per album and per file; null otherwise. */
    private MessageObject toTile(TLRPC.Message message, long chatId) {
        if (message == null || message instanceof TLRPC.TL_messageService) {
            return null;
        }
        final MessageObject object = new MessageObject(currentAccount, message, false, false);
        if (object.photoThumbs == null || object.photoThumbs.isEmpty() || object.needDrawBluredPreview() || object.isRoundVideo()) {
            return null;
        }
        if (TigiFeedPrefs.isNotInterested(currentAccount, chatId, object.getId()) || TigiFeedPrefs.isHidden(currentAccount, chatId)) {
            return null;
        }
        final String key = object.getGroupId() != 0 ? "g" + object.getGroupId() : object.getDocument() != null ? "d" + object.getDocument().id : "m" + chatId + "_" + object.getId();
        if (!seen.add(key)) {
            return null;
        }
        return object;
    }

    private void countTags(MessageObject object) {
        final CharSequence caption = object.caption;
        if (TextUtils.isEmpty(caption)) {
            return;
        }
        final Matcher matcher = HASHTAG.matcher(caption);
        final HashSet<String> inThisPost = new HashSet<>();
        while (matcher.find()) {
            final String tag = matcher.group(1).toLowerCase();
            if (inThisPost.add(tag)) {
                final Integer count = tagCounts.get(tag);
                tagCounts.put(tag, count == null ? 1 : count + 1);
            }
        }
    }

    /* clicks */

    private void onItemClick(int position) {
        if (mode == MODE_SEARCH) {
            if (position >= 0 && position < found.size()) {
                final TLObject object = found.get(position);
                final long did = object instanceof TLRPC.User ? ((TLRPC.User) object).id : -((TLRPC.Chat) object).id;
                if (did < 0) {
                    TigiFeedPrefs.bumpAffinity(currentAccount, -did);
                }
                TigiNavigation.openProfile(this, did);
            }
            return;
        }
        final ArrayList<MessageObject> tiles = mode == MODE_HASHTAG ? tagged : forYou;
        if (position < 0 || position >= tiles.size()) {
            return;
        }
        final MessageObject message = tiles.get(position);
        final long chatId = -message.getDialogId();
        TigiFeedPrefs.bumpAffinity(currentAccount, chatId);
        final Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        args.putInt("start_message_id", message.getId());
        presentFragment(new ChannelFeedActivity(args));
    }

    /* adapter */

    private class Adapter extends RecyclerListView.SelectionAdapter {
        static final int TYPE_TILE = 0;
        static final int TYPE_ROW = 1;
        static final int TYPE_LOADING = 2;
        static final int TYPE_STATE = 3;

        private int itemsCount() {
            return mode == MODE_SEARCH ? found.size() : mode == MODE_HASHTAG ? tagged.size() : forYou.size();
        }

        @Override
        public int getItemCount() {
            final int count = itemsCount();
            return count == 0 ? 1 : count;
        }

        @Override
        public int getItemViewType(int position) {
            if (itemsCount() == 0) {
                return loading || mode == MODE_FOR_YOU && !channelsLoaded && errorText == null ? TYPE_LOADING : TYPE_STATE;
            }
            return mode == MODE_SEARCH ? TYPE_ROW : TYPE_TILE;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == TYPE_TILE || holder.getItemViewType() == TYPE_ROW;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_TILE) {
                view = new Tile(parent.getContext());
            } else if (viewType == TYPE_ROW) {
                view = new UserCell(parent.getContext(), 8, 0, false);
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (viewType == TYPE_LOADING) {
                final FlickerLoadingView flicker = new FlickerLoadingView(parent.getContext());
                flicker.setViewType(mode == MODE_SEARCH ? FlickerLoadingView.DIALOG_TYPE : FlickerLoadingView.PHOTOS_TYPE);
                flicker.setIsSingleCell(false);
                view = flicker;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(560)));
            } else {
                final TextView state = new TextView(parent.getContext());
                state.setGravity(Gravity.CENTER);
                state.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                state.setPadding(dp(32), dp(64), dp(32), dp(64));
                view = state;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final int type = holder.getItemViewType();
            if (type == TYPE_TILE) {
                final ArrayList<MessageObject> tiles = mode == MODE_HASHTAG ? tagged : forYou;
                ((Tile) holder.itemView).bind(tiles.get(position));
            } else if (type == TYPE_ROW) {
                final TLObject object = found.get(position);
                final UserCell cell = (UserCell) holder.itemView;
                if (object instanceof TLRPC.User) {
                    final TLRPC.User user = (TLRPC.User) object;
                    final String username = UserObject.getPublicUsername(user);
                    cell.setData(user, UserObject.getUserName(user), TextUtils.isEmpty(username) ? null : "@" + username, 0, position != found.size() - 1);
                } else {
                    final TLRPC.Chat chat = (TLRPC.Chat) object;
                    final String status = chat.participants_count > 0
                        ? LocaleController.formatPluralStringComma(ChatObject.isChannelAndNotMegaGroup(chat) ? "Subscribers" : "Members", chat.participants_count)
                        : null;
                    cell.setData(chat, chat.title, status, 0, position != found.size() - 1);
                }
            } else if (type == TYPE_STATE) {
                final TextView state = (TextView) holder.itemView;
                state.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                if (errorText != null) {
                    state.setText(errorText);
                } else if (mode == MODE_SEARCH) {
                    state.setText(LocaleController.getString(R.string.NilakExploreNoResults));
                } else if (mode == MODE_HASHTAG) {
                    state.setText(LocaleController.formatString(R.string.NilakExploreNoTagPosts, hashtag));
                } else {
                    state.setText(LocaleController.getString(R.string.NilakExploreEmpty));
                }
            }
        }
    }

    private class Tile extends FrameLayout {
        private final BackupImageView imageView;
        private final ImageView videoIcon;

        Tile(Context context) {
            super(context);
            setPadding(dp(1), dp(1), dp(1), dp(1));
            imageView = new BackupImageView(context);
            imageView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            videoIcon = new ImageView(context);
            videoIcon.setImageResource(R.drawable.tigi_reels_24);
            videoIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            addView(videoIcon, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // 4:5 tiles, like Instagram's grid
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 5 / 4, MeasureSpec.EXACTLY));
        }

        void bind(MessageObject message) {
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 320);
            imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "200_250", (android.graphics.drawable.Drawable) null, message.photoThumbsObject);
            videoIcon.setVisibility(message.isVideo() ? VISIBLE : GONE);
            final TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
            setContentDescription((chat != null ? chat.title + ", " : "") + LocaleController.getString(message.isVideo() ? R.string.AttachVideo : R.string.AttachPhoto));
        }
    }
}
