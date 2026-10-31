package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Nilak article composer: a title and paragraphs, published as a Telegraph page (telegra.ph, Telegram's own free
 * publishing service, opened in the Instant View reader) and posted to the user's Nilak channel as a link post, so
 * it shows in followers' feeds and in their Read feed. The Telegraph account is created on first use and its
 * token kept on the phone; the draft is kept until it is published.
 */
public class TigiArticleActivity extends BaseFragment {

    private static final String KEY_TOKEN = "nilak_telegraph_token";
    private static final String KEY_DRAFT_TITLE = "nilak_article_draft_title";
    private static final String KEY_DRAFT_BODY = "nilak_article_draft_body";
    private static final int MAX_TITLE = 256;

    private long chatId;
    private EditTextBoldCursor titleInput;
    private EditTextBoldCursor bodyInput;
    private TextView publishButton;
    private TextView countView;
    private boolean publishing;

    public TigiArticleActivity() {
        super();
    }

    public TigiArticleActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        chatId = arguments != null ? arguments.getLong("chat_id", 0) : 0;
        if (chatId == 0) {
            final TLRPC.Chat target = TigiPosting.defaultChannel(currentAccount);
            chatId = target != null ? target.id : 0;
        }
        return super.onFragmentCreate();
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(currentAccount);
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> finishFragment());
        bar.addView(close, LayoutHelper.createLinear(48, 56));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakNewArticle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 8, 0));
        publishButton = new TextView(context);
        publishButton.setText(LocaleController.getString(R.string.NilakPublish));
        publishButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        publishButton.setTypeface(TigiStyle.displayBold());
        publishButton.setTextColor(0xFFFFFFFF);
        publishButton.setGravity(Gravity.CENTER);
        publishButton.setPadding(dp(16), 0, dp(16), 0);
        publishButton.setBackground(TigiStyle.primaryPill(18));
        publishButton.setOnClickListener(v -> confirmPublish());
        bar.addView(publishButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36, 0, 0, 12, 0));
        column.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        final ScrollView scroll = new ScrollView(context);
        final LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), dp(8), dp(18), dp(24));

        titleInput = new EditTextBoldCursor(context);
        titleInput.setHint(LocaleController.getString(R.string.NilakArticleTitleHint));
        titleInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleInput.setTypeface(TigiStyle.displayExtraBold());
        titleInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        titleInput.setCursorColor(TigiStyle.accent());
        titleInput.setBackground(null);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        titleInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(MAX_TITLE)});
        titleInput.setText(prefs().getString(KEY_DRAFT_TITLE, ""));
        fields.addView(titleInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        bodyInput = new EditTextBoldCursor(context);
        bodyInput.setHint(LocaleController.getString(R.string.NilakArticleBodyHint));
        bodyInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        bodyInput.setLineSpacing(dp(4), 1f);
        bodyInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        bodyInput.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        bodyInput.setCursorColor(TigiStyle.accent());
        bodyInput.setBackground(null);
        bodyInput.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        bodyInput.setMinLines(8);
        bodyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        bodyInput.setText(prefs().getString(KEY_DRAFT_BODY, ""));
        fields.addView(bodyInput, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        countView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fields.addView(countView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final TextView note = new TextView(context);
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        note.setText(chat != null ? LocaleController.formatString(R.string.NilakArticleNote, chat.title) : LocaleController.getString(R.string.TigiPostNoRights));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        fields.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        scroll.addView(fields);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateState();
            }
        };
        titleInput.addTextChangedListener(watcher);
        bodyInput.addTextChangedListener(watcher);
        updateState();
        fragmentView = root;
        return root;
    }

    private void updateState() {
        final String body = bodyInput.getText().toString().trim();
        final int words = body.isEmpty() ? 0 : body.split("\\s+").length;
        countView.setText(LocaleController.formatString(R.string.NilakArticleCount, words, Math.max(1, Math.round(words / 200f))));
        final boolean ready = !publishing && titleInput.length() > 0 && body.length() > 0 && chatId != 0;
        publishButton.setEnabled(ready);
        publishButton.setAlpha(ready ? 1f : 0.5f);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveDraft();
    }

    private void saveDraft() {
        if (titleInput == null || publishing) {
            return;
        }
        prefs().edit().putString(KEY_DRAFT_TITLE, titleInput.getText().toString()).putString(KEY_DRAFT_BODY, bodyInput.getText().toString()).apply();
    }

    private void confirmPublish() {
        final TLRPC.Chat chat = getMessagesController().getChat(chatId);
        if (chat == null || !TigiPosting.canPostTo(chat)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiPostNoRights)).show();
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.NilakPublish));
        builder.setMessage(LocaleController.formatString(R.string.NilakArticleConfirm, chat.title));
        builder.setPositiveButton(LocaleController.getString(R.string.NilakPublish), (d, w) -> publish(chat));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void publish(TLRPC.Chat chat) {
        if (publishing) {
            return;
        }
        publishing = true;
        updateState();
        AndroidUtilities.hideKeyboard(bodyInput);
        final String title = titleInput.getText().toString().trim();
        final String body = bodyInput.getText().toString().trim();
        final TLRPC.User me = getUserConfig().getCurrentUser();
        final String author = me != null ? UserObject.getUserName(me) : "";
        final String token = prefs().getString(KEY_TOKEN, null);
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        Utilities.globalQueue.postRunnable(() -> {
            String url = null;
            String accessToken = token;
            try {
                if (TextUtils.isEmpty(accessToken)) {
                    final JSONObject account = call("createAccount", "short_name=" + enc("nilak") + "&author_name=" + enc(author));
                    accessToken = account.getString("access_token");
                }
                final JSONArray content = new JSONArray();
                for (String paragraph : body.split("\\n\\s*\\n")) {
                    final String text = paragraph.trim();
                    if (text.isEmpty()) {
                        continue;
                    }
                    final JSONObject node = new JSONObject();
                    node.put("tag", "p");
                    node.put("children", new JSONArray().put(text));
                    content.put(node);
                }
                final JSONObject page = call("createPage", "access_token=" + enc(accessToken) + "&title=" + enc(title)
                    + "&author_name=" + enc(author) + "&content=" + enc(content.toString()) + "&return_content=false");
                url = page.getString("url");
            } catch (Exception e) {
                FileLog.e(e);
            }
            final String finalUrl = url;
            final String finalToken = accessToken;
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                publishing = false;
                if (isFinishing() || getParentActivity() == null) {
                    return;
                }
                if (finalUrl == null) {
                    updateState();
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakArticleFailed)).show();
                    return;
                }
                prefs().edit().putString(KEY_TOKEN, finalToken).remove(KEY_DRAFT_TITLE).remove(KEY_DRAFT_BODY).apply();
                // the post: title, a short excerpt and the link, which shows as an Instant View preview
                final String excerpt = body.length() > 200 ? body.substring(0, 200).trim() + "…" : body;
                final String text = title + "\n\n" + excerpt + "\n\n" + finalUrl;
                SendMessagesHelper.getInstance(currentAccount).sendMessage(SendMessagesHelper.SendMessageParams.of(text, -chat.id, null, null, null, true, null, null, null, true, 0, 0, null, false));
                TigiPosting.setLastChannel(currentAccount, chat.id);
                titleInput.setText("");
                bodyInput.setText("");
                final MainTabsActivity tabs = TigiNavigation.findMainTabs(this);
                if (tabs != null) {
                    tabs.openHomeTab();
                    BulletinFactory.of(tabs).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.NilakArticlePublished)).show();
                }
                finishFragment();
            });
        });
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    /** One Telegraph API call (form POST); returns "result" or throws. */
    private static JSONObject call(String method, String form) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL("https://api.telegra.ph/" + method).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(form.getBytes("UTF-8"));
            }
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                final byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
            }
            final JSONObject json = new JSONObject(buffer.toString("UTF-8"));
            if (!json.optBoolean("ok")) {
                throw new IllegalStateException(json.optString("error"));
            }
            return json.getJSONObject("result");
        } finally {
            connection.disconnect();
        }
    }

    /** From Create: write an article for the given channel. */
    public static void start(BaseFragment fragment, TLRPC.Chat chat) {
        if (fragment == null || chat == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", chat.id);
        fragment.presentFragment(new TigiArticleActivity(args));
    }
}
