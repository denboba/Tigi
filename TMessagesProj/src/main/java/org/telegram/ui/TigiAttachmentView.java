package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Nilak: the parts of a post that are not photos or videos, shown in the card itself: polls (vote and results),
 * music and voice (play / pause), documents (download and open) and link previews. Everything else stays with the
 * card's "View in chat" fallback.
 */
public class TigiAttachmentView extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    public static final int KIND_NONE = 0;
    public static final int KIND_POLL = 1;
    public static final int KIND_AUDIO = 2;
    public static final int KIND_DOCUMENT = 3;
    public static final int KIND_LINK = 4;
    public static final int KIND_ARTICLE = 5;
    public static final int KIND_STICKER = 6;

    private final BaseFragment fragment;
    private MessageObject message;
    private int kind;
    private final ArrayList<byte[]> pickedOptions = new ArrayList<>();
    private boolean voted;
    private ImageView playButton;
    private TextView documentStatus;
    private AudioProgressView audioProgress;
    private TextView audioTime;
    private BackupImageView stickerView;
    private TextView stickerFallback;
    private View downloadBar;
    private TextView documentPages;
    private BackupImageView documentPreview;
    private boolean openWhenDownloaded;
    private int pageCount;
    private final Runnable playState = new Runnable() {
        @Override
        public void run() {
            updatePlayButton();
            updateAudioProgress();
            if (isAttachedToWindow() && kind == KIND_AUDIO && message != null && MediaController.getInstance().isPlayingMessage(message)) {
                AndroidUtilities.runOnUIThread(this, 250);
            }
        }
    };

    public TigiAttachmentView(Context context, BaseFragment fragment) {
        super(context);
        this.fragment = fragment;
        setOrientation(VERTICAL);
        setVisibility(GONE);
    }

    /** What kind of attachment this message has, as far as the card can show it. */
    public static int kindOf(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return KIND_NONE;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        if (media instanceof TLRPC.TL_messageMediaPoll && ((TLRPC.TL_messageMediaPoll) media).poll != null) {
            return KIND_POLL;
        }
        if (isBigEmoji(message) || message.isSticker() || message.isAnimatedSticker()) {
            return KIND_STICKER;
        }
        if (message.isMusic() || message.isVoice()) {
            return KIND_AUDIO;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage && ((TLRPC.TL_messageMediaWebPage) media).webpage instanceof TLRPC.TL_webPage) {
            final TLRPC.TL_webPage page = (TLRPC.TL_webPage) ((TLRPC.TL_messageMediaWebPage) media).webpage;
            return !TextUtils.isEmpty(page.title) || !TextUtils.isEmpty(page.description) ? KIND_LINK : KIND_NONE;
        }
        if (TigiArticle.isArticle(message)) {
            return KIND_ARTICLE;
        }
        if (message.getDocument() != null && !message.isVideo() && !message.isGif() && !message.isSticker()
            && !message.isAnimatedSticker() && !message.isRoundVideo()) {
            return KIND_DOCUMENT;
        }
        return KIND_NONE;
    }

    /** A text post of one emoji that Telegram animates (an animated emoji or one custom emoji), shown big. */
    public static boolean isBigEmoji(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        return (media == null || media instanceof TLRPC.TL_messageMediaEmpty) && (message.emojiAnimatedSticker != null || message.emojiAnimatedStickerId != null);
    }

    /** Shows the message's attachment; returns false (and hides) when the card cannot show it. */
    public boolean bind(MessageObject message) {
        this.message = message;
        kind = kindOf(message);
        removeAllViews();
        playButton = null;
        documentStatus = null;
        audioProgress = null;
        audioTime = null;
        stickerView = null;
        stickerFallback = null;
        downloadBar = null;
        documentPages = null;
        documentPreview = null;
        openWhenDownloaded = false;
        pageCount = 0;
        switch (kind) {
            case KIND_STICKER:
                buildSticker();
                break;
            case KIND_POLL:
                buildPoll();
                if (isAttachedToWindow()) {
                    refreshPollResults(); // a recycled card now showing another poll
                }
                break;
            case KIND_AUDIO:
                buildAudio();
                break;
            case KIND_DOCUMENT:
                buildDocument();
                break;
            case KIND_LINK:
                buildLink();
                break;
            case KIND_ARTICLE:
                buildArticle();
                break;
            default:
                setVisibility(GONE);
                return false;
        }
        setVisibility(VISIBLE);
        return true;
    }

    private int text() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int gray() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
    }

    private android.graphics.drawable.Drawable box() {
        return Theme.createRoundRectDrawable(dp(14), TigiLilac.inset());
    }

    /* polls */

    private void buildPoll() {
        final TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) MessageObject.getMedia(message.messageOwner);
        final TLRPC.Poll poll = media.poll;
        final TLRPC.PollResults results = media.results;
        voted = false;
        int total = results != null ? results.total_voters : 0;
        if (results != null && results.results != null) {
            for (int i = 0; i < results.results.size(); i++) {
                if (results.results.get(i).chosen) {
                    voted = true;
                }
            }
        }
        final boolean showResults = voted || poll.closed || message.getId() <= 0;
        setPadding(dp(12), dp(4), dp(12), dp(8));
        setBackground(null);

        final TextView question = new TextView(getContext());
        question.setText(poll.question != null ? poll.question.text : "");
        question.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        question.setTypeface(TigiStyle.displayBold());
        question.setTextColor(text());
        addView(question, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
        final TextView meta = new TextView(getContext());
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        meta.setTextColor(gray());
        final String type = LocaleController.getString(poll.quiz ? R.string.QuizPoll : poll.public_voters ? R.string.PublicPoll : R.string.AnonymousPoll);
        meta.setText(poll.closed ? LocaleController.getString(R.string.FinalResults) : type);
        addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        pickedOptions.clear();
        for (int i = 0; i < poll.answers.size(); i++) {
            final TLRPC.PollAnswer answer = poll.answers.get(i);
            int votes = 0;
            boolean chosen = false;
            if (results != null && results.results != null) {
                for (int r = 0; r < results.results.size(); r++) {
                    final TLRPC.PollAnswerVoters v = results.results.get(r);
                    if (java.util.Arrays.equals(v.option, answer.option)) {
                        votes = v.voters;
                        chosen = v.chosen;
                    }
                }
            }
            addView(showResults ? resultRow(answer, votes, total, chosen) : optionRow(poll, answer),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 6));
        }
        if (!showResults && poll.multiple_choice) {
            final TextView vote = new TextView(getContext());
            vote.setText(LocaleController.getString(R.string.NilakVote));
            vote.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            vote.setTypeface(TigiStyle.displayBold());
            vote.setTextColor(0xFFFFFFFF);
            vote.setGravity(Gravity.CENTER);
            vote.setBackground(TigiStyle.primaryPill(10));
            vote.setOnClickListener(v -> submitVote());
            addView(vote, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 4, 0, 0));
        }
        final TextView footer = new TextView(getContext());
        footer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        footer.setTextColor(gray());
        footer.setText(total > 0 ? LocaleController.formatPluralString(poll.quiz ? "Answer" : "Vote", total) : LocaleController.getString(poll.quiz ? R.string.NoVotesQuiz : R.string.NoVotes));
        addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
    }

    private View optionRow(TLRPC.Poll poll, TLRPC.PollAnswer answer) {
        final TextView option = new TextView(getContext());
        option.setText(answer.text != null ? answer.text.text : "");
        option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        option.setTextColor(text());
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(14), 0, dp(14), 0);
        option.setSingleLine(true);
        option.setEllipsize(TextUtils.TruncateAt.END);
        option.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), TigiLilac.inset(), Theme.multAlpha(TigiStyle.accent(), 0.18f)));
        option.setOnClickListener(v -> {
            if (poll.multiple_choice) {
                if (!removeOption(answer.option)) {
                    pickedOptions.add(answer.option);
                }
                option.setBackground(containsOption(answer.option) ? TigiStyle.tintedPill(12)
                    : Theme.createSimpleSelectorRoundRectDrawable(dp(12), TigiLilac.inset(), Theme.multAlpha(TigiStyle.accent(), 0.18f)));
                option.setTextColor(containsOption(answer.option) ? TigiStyle.accent() : text());
            } else {
                pickedOptions.clear();
                pickedOptions.add(answer.option);
                submitVote();
            }
        });
        return option;
    }

    private boolean containsOption(byte[] option) {
        for (byte[] o : pickedOptions) {
            if (java.util.Arrays.equals(o, option)) {
                return true;
            }
        }
        return false;
    }

    private boolean removeOption(byte[] option) {
        for (int i = 0; i < pickedOptions.size(); i++) {
            if (java.util.Arrays.equals(pickedOptions.get(i), option)) {
                pickedOptions.remove(i);
                return true;
            }
        }
        return false;
    }

    private void submitVote() {
        if (pickedOptions.isEmpty() || message == null || message.getId() <= 0) {
            return;
        }
        final TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) MessageObject.getMedia(message.messageOwner);
        final ArrayList<TLRPC.PollAnswer> answers = new ArrayList<>();
        for (int i = 0; i < media.poll.answers.size(); i++) {
            if (containsOption(media.poll.answers.get(i).option)) {
                answers.add(media.poll.answers.get(i));
            }
        }
        // show the vote right away; the server's results replace these numbers with the next update
        if (media.results == null) {
            media.results = new TLRPC.TL_pollResults();
        }
        boolean found;
        for (TLRPC.PollAnswer answer : answers) {
            found = false;
            for (int r = 0; r < media.results.results.size(); r++) {
                final TLRPC.PollAnswerVoters v = media.results.results.get(r);
                if (java.util.Arrays.equals(v.option, answer.option)) {
                    v.chosen = true;
                    v.voters++;
                    found = true;
                }
            }
            if (!found) {
                final TLRPC.TL_pollAnswerVoters v = new TLRPC.TL_pollAnswerVoters();
                v.option = answer.option;
                v.chosen = true;
                v.voters = 1;
                media.results.results.add(v);
            }
        }
        media.results.total_voters++;
        // no results fetch before the vote lands: stale numbers would wipe the vote just shown
        message.pollLastCheckTime = android.os.SystemClock.elapsedRealtime();
        SendMessagesHelper.getInstance(fragment.getCurrentAccount()).sendVote(message, answers, () -> {
            // the vote's own updates normally carry the new results; ask once more so the numbers never stay local
            message.pollLastCheckTime = 0;
            refreshPollResults();
        });
        bind(message);
    }

    private View resultRow(TLRPC.PollAnswer answer, int votes, int total, boolean chosen) {
        final int percent = total > 0 ? Math.round(votes * 100f / total) : 0;
        final FrameLayout row = new FrameLayout(getContext());
        row.setBackground(Theme.createRoundRectDrawable(dp(12), TigiLilac.inset()));
        final View fill = new View(getContext());
        fill.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(TigiStyle.accent(), chosen ? 0.28f : 0.14f)));
        row.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        row.post(() -> {
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fill.getLayoutParams();
            params.width = (int) (row.getWidth() * percent / 100f);
            fill.setLayoutParams(params);
        });
        final TextView label = new TextView(getContext());
        label.setText((chosen ? "✓ " : "") + (answer.text != null ? answer.text.text : ""));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(text());
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 14, 0, 56, 0));
        final TextView value = new TextView(getContext());
        value.setText(percent + "%");
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        value.setTypeface(TigiStyle.displayBold());
        value.setTextColor(chosen ? TigiStyle.accent() : text());
        value.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.addView(value, LayoutHelper.createFrame(52, LayoutHelper.MATCH_PARENT, Gravity.RIGHT, 0, 0, 12, 0));
        row.setContentDescription(label.getText() + ", " + percent + "%");
        return row;
    }

    /* stickers and big emoji */

    /** A sticker (static, animated or video) or a single animated emoji, drawn big and animated as in chats. */
    private void buildSticker() {
        setPadding(dp(12), dp(4), dp(12), dp(8));
        setBackground(null);
        final FrameLayout frame = new FrameLayout(getContext());
        stickerView = new BackupImageView(getContext());
        stickerView.setAspectFit(true);
        stickerView.getImageReceiver().setAutoRepeat(1);
        frame.addView(stickerView, LayoutHelper.createFrame(176, 176, Gravity.CENTER));
        // the emoji itself, big, while its animation is fetched (or if it has none)
        stickerFallback = new TextView(getContext());
        stickerFallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 72);
        stickerFallback.setGravity(Gravity.CENTER);
        stickerFallback.setVisibility(GONE);
        stickerFallback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(stickerFallback, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        frame.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        frame.setOnClickListener(v -> onStickerTap());
        final String alt = stickerAlt();
        frame.setContentDescription(TextUtils.isEmpty(alt) ? LocaleController.getString(R.string.AttachSticker) : alt + " " + LocaleController.getString(R.string.AttachSticker));
        addView(frame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 184));
        bindStickerImage();
    }

    private String stickerAlt() {
        if (isBigEmoji(message)) {
            return message.messageText != null ? message.messageText.toString() : null;
        }
        final TLRPC.Document document = message.getDocument();
        if (document != null) {
            for (int i = 0; i < document.attributes.size(); i++) {
                if (document.attributes.get(i) instanceof TLRPC.TL_documentAttributeSticker) {
                    return document.attributes.get(i).alt;
                }
            }
        }
        return null;
    }

    private void bindStickerImage() {
        if (stickerView == null || message == null) {
            return;
        }
        final TLRPC.Document document = message.getDocument();
        if (document == null) {
            // a custom emoji whose file is still being looked up: the emoji itself until it arrives
            message.loadAnimatedEmojiDocument();
            stickerView.setImageDrawable(null);
            if (stickerFallback != null && message.messageText != null) {
                stickerFallback.setText(org.telegram.messenger.Emoji.replaceEmoji(message.messageText.toString(), stickerFallback.getPaint().getFontMetricsInt(), false));
                stickerFallback.setVisibility(VISIBLE);
            }
            return;
        }
        if (stickerFallback != null) {
            stickerFallback.setVisibility(GONE);
        }
        final org.telegram.messenger.ImageReceiver receiver = stickerView.getImageReceiver();
        if (MessageObject.isTextColorEmoji(document)) {
            stickerView.setColorFilter(Theme.getAnimatedEmojiColorFilter(fragment != null ? fragment.getResourceProvider() : null));
        } else {
            stickerView.setColorFilter(null);
        }
        final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90);
        final org.telegram.messenger.SvgHelper.SvgDrawable svgThumb = org.telegram.messenger.DocumentObject.getSvgThumb(document, Theme.key_windowBackgroundGray, 1.0f);
        final String color = isBigEmoji(message) && message.emojiAnimatedStickerColor != null ? message.emojiAnimatedStickerColor : "";
        final String filter = "176_176" + color;
        if (MessageObject.canAutoplayAnimatedSticker(document)) {
            if (svgThumb != null) {
                receiver.setImage(ImageLocation.getForDocument(document), filter, ImageLocation.getForDocument(thumb, document), null, null, null, svgThumb, 0, null, message, 1);
            } else if (thumb != null) {
                receiver.setImage(ImageLocation.getForDocument(document), filter, ImageLocation.getForDocument(thumb, document), null, (String) null, message, 1);
            } else {
                receiver.setImage(ImageLocation.getForDocument(document), filter, null, null, message, 1);
            }
        } else if (svgThumb != null) {
            receiver.setImage(ImageLocation.getForDocument(document), filter, svgThumb, "webp", message, 1);
        } else {
            receiver.setImage(ImageLocation.getForDocument(document), filter, null, "webp", message, 1);
        }
    }

    /** A sticker opens its pack (add it, or send one); a big emoji plays again. */
    private void onStickerTap() {
        if (message == null || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (isBigEmoji(message)) {
            final org.telegram.ui.Components.RLottieDrawable lottie = stickerView != null ? stickerView.getImageReceiver().getLottieAnimation() : null;
            if (lottie != null) {
                lottie.setCurrentFrame(0, false);
                lottie.start();
            }
            return;
        }
        final TLRPC.InputStickerSet set = MessageObject.getInputStickerSet(message.messageOwner);
        if (set != null) {
            fragment.showDialog(new org.telegram.ui.Components.StickersAlert(fragment.getParentActivity(), fragment, set, null, null, fragment.getResourceProvider(), false));
        }
    }

    /* music and voice */

    /** Music: cover, title, artist, a progress bar to seek and the time; voice: the same without the cover. */
    private void buildAudio() {
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(null);
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(14), dp(10));
        row.setBackground(box());
        final FrameLayout left = new FrameLayout(getContext());
        final TLRPC.Document document = message.getDocument();
        final TLRPC.PhotoSize coverThumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320) : null;
        // only the cover inside the file: no lookup of the song on an outside service (privacy)
        if (message.isMusic() && coverThumb != null) {
            final BackupImageView cover = new BackupImageView(getContext());
            cover.setRoundRadius(dp(12));
            cover.setImage(ImageLocation.getForDocument(coverThumb, document), "112_112", (android.graphics.drawable.Drawable) null, message);
            left.addView(cover, LayoutHelper.createFrame(56, 56));
            playButton = new ImageView(getContext());
            playButton.setScaleType(ImageView.ScaleType.CENTER);
            playButton.setBackground(Theme.createRoundRectDrawable(dp(12), 0x66000000));
            playButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            left.addView(playButton, LayoutHelper.createFrame(56, 56));
        } else {
            // no cover: a tile in the accent colour for music (like an album without art), a round button for voice
            playButton = new ImageView(getContext());
            playButton.setScaleType(ImageView.ScaleType.CENTER);
            if (message.isMusic()) {
                playButton.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{TigiStyle.accent(), Theme.multAlpha(TigiStyle.accent(), 0.7f)}) {{
                    setCornerRadius(dp(12));
                }});
                left.addView(playButton, LayoutHelper.createFrame(56, 56));
            } else {
                playButton.setBackground(Theme.createRoundRectDrawable(dp(26), TigiStyle.accent()));
                left.addView(playButton, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
            }
            playButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }
        playButton.setOnClickListener(v -> togglePlay());
        row.addView(left, LayoutHelper.createLinear(56, 56));
        final LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        final TextView title = new TextView(getContext());
        final TextView subtitle = new TextView(getContext());
        if (message.isMusic()) {
            title.setText(message.getMusicTitle());
            subtitle.setText(message.getMusicAuthor());
        } else {
            title.setText(LocaleController.getString(R.string.AttachAudio));
            subtitle.setVisibility(GONE);
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(text());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(gray());
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(subtitle);
        audioProgress = new AudioProgressView(getContext());
        texts.addView(audioProgress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 2, 0, 0));
        audioTime = new TextView(getContext());
        audioTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        audioTime.setTextColor(gray());
        audioTime.setSingleLine(true);
        texts.addView(audioTime);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        row.setContentDescription(title.getText() + (message.isMusic() ? ", " + subtitle.getText() : ""));
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updatePlayButton();
        updateAudioProgress();
    }

    private void togglePlay() {
        if (message == null) {
            return;
        }
        final MediaController controller = MediaController.getInstance();
        if (controller.isPlayingMessage(message) && !controller.isMessagePaused()) {
            controller.pauseMessage(message);
        } else {
            controller.playMessage(message);
        }
        AndroidUtilities.runOnUIThread(playState, 150);
    }

    private void updatePlayButton() {
        if (playButton == null || message == null) {
            return;
        }
        final MediaController controller = MediaController.getInstance();
        final boolean playing = controller.isPlayingMessage(message) && !controller.isMessagePaused();
        playButton.setImageResource(playing ? R.drawable.tigi_pause_24 : R.drawable.tigi_play_24);
        playButton.setContentDescription(LocaleController.getString(playing ? R.string.AccActionPause : R.string.AccActionPlay));
    }

    /** The playing copy of this message (MediaController may hold another object for the same post). */
    private MessageObject playingCopy() {
        final MediaController controller = MediaController.getInstance();
        return message != null && controller.isPlayingMessage(message) ? controller.getPlayingMessageObject() : null;
    }

    private void updateAudioProgress() {
        if (audioProgress == null || message == null) {
            return;
        }
        final int duration = (int) message.getDuration();
        final MessageObject playing = playingCopy();
        if (playing != null) {
            audioProgress.setProgress(playing.audioProgress);
            audioTime.setText(AndroidUtilities.formatShortDuration(playing.audioProgressSec) + " / " + AndroidUtilities.formatShortDuration(duration));
        } else {
            audioProgress.setProgress(0);
            audioTime.setText(AndroidUtilities.formatShortDuration(duration));
        }
    }

    /** A thin rounded progress line; tapping or dragging on it seeks while the track plays (or starts it there). */
    private class AudioProgressView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float progress;

        AudioProgressView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float h = dp(4);
            final float top = (getHeight() - h) / 2f;
            paint.setColor(Theme.multAlpha(gray(), 0.35f));
            rect.set(0, top, getWidth(), top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            paint.setColor(TigiStyle.accent());
            rect.set(0, top, getWidth() * progress, top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            if (playingCopy() != null) {
                canvas.drawCircle(Math.max(dp(5), Math.min(getWidth() - dp(5), getWidth() * progress)), getHeight() / 2f, dp(5), paint);
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (message == null || getWidth() <= 0) {
                return false;
            }
            final float value = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    setProgress(value);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    setProgress(value);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    final MediaController controller = MediaController.getInstance();
                    if (!controller.isPlayingMessage(message)) {
                        controller.playMessage(message);
                    }
                    final MessageObject playing = playingCopy();
                    if (playing != null) {
                        controller.seekToProgress(playing, value);
                    }
                    AndroidUtilities.runOnUIThread(playState, 150);
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    updateAudioProgress();
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (kind == KIND_AUDIO) {
            AndroidUtilities.runOnUIThread(playState);
        }
        if (fragment != null) {
            final NotificationCenter center = NotificationCenter.getInstance(fragment.getCurrentAccount());
            center.addObserver(this, NotificationCenter.didUpdatePollResults);
            center.addObserver(this, NotificationCenter.animatedEmojiDocumentLoaded);
            center.addObserver(this, NotificationCenter.fileLoaded);
            center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
            center.addObserver(this, NotificationCenter.fileLoadFailed);
        }
        refreshPollResults();
        if (kind == KIND_STICKER && message != null && message.emojiAnimatedSticker == null && message.emojiAnimatedStickerId != null) {
            message.loadAnimatedEmojiDocument();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(playState);
        if (fragment != null) {
            final NotificationCenter center = NotificationCenter.getInstance(fragment.getCurrentAccount());
            center.removeObserver(this, NotificationCenter.didUpdatePollResults);
            center.removeObserver(this, NotificationCenter.animatedEmojiDocumentLoaded);
            center.removeObserver(this, NotificationCenter.fileLoaded);
            center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            center.removeObserver(this, NotificationCenter.fileLoadFailed);
        }
    }

    /** Server poll results (after a vote, or anyone else's): applied to this card's poll at once. */
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.animatedEmojiDocumentLoaded) {
            if (kind == KIND_STICKER && args.length > 0 && args[0] == message) {
                bindStickerImage();
            }
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadProgressChanged || id == NotificationCenter.fileLoadFailed) {
            onFileEvent(id, args);
            return;
        }
        if (id != NotificationCenter.didUpdatePollResults || kind != KIND_POLL || message == null || args.length < 3) {
            return;
        }
        final TLRPC.MessageMedia m = MessageObject.getMedia(message.messageOwner);
        if (!(m instanceof TLRPC.TL_messageMediaPoll)) {
            return;
        }
        final TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) m;
        if (media.poll == null || !(args[0] instanceof Long) || media.poll.id != (Long) args[0]) {
            return;
        }
        if (args[1] instanceof TLRPC.Poll) {
            media.poll = (TLRPC.Poll) args[1];
        }
        if (args[2] instanceof TLRPC.PollResults) {
            MessageObject.updatePollResults(media, (TLRPC.PollResults) args[2]);
        }
        pickedOptions.clear();
        bind(message);
    }

    /** Fresh results when a poll card comes on screen, at most every 30 seconds per post. */
    private void refreshPollResults() {
        if (kind != KIND_POLL || message == null || message.getId() <= 0 || fragment == null) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (message.pollLastCheckTime != 0 && now - message.pollLastCheckTime < 30000) {
            return;
        }
        message.pollLastCheckTime = now;
        final int account = fragment.getCurrentAccount();
        final TLRPC.TL_messages_getPollResults req = new TLRPC.TL_messages_getPollResults();
        req.peer = MessagesController.getInstance(account).getInputPeer(message.getDialogId());
        req.msg_id = message.getId();
        req.poll_hash = message.getPollHash();
        if (req.peer == null) {
            return;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
        });
    }

    /* documents */

    /** A Nilak article: label, title, lead and a Read button; opens the native reader. */
    private void buildArticle() {
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(null);
        final LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.multAlpha(TigiStyle.accent(), Theme.isCurrentThemeDark() ? 0.18f : 0.08f)));
        final TextView label = new TextView(getContext());
        label.setText(LocaleController.getString(R.string.NilakArticleLabel).toUpperCase(Locale.getDefault()));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setTypeface(TigiStyle.displayExtraBold());
        label.setTextColor(TigiStyle.accent());
        box.addView(label);
        final TextView title = new TextView(getContext());
        title.setText(TigiArticle.titleOf(message));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        // Lilac: article titles in a serif, like a magazine; the app's display font otherwise
        title.setTypeface(TigiLilac.on() ? android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD) : TigiStyle.displayExtraBold());
        title.setTextColor(text());
        title.setLineSpacing(dp(1), 1f);
        box.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final String lead = TigiArticle.subtitleOf(message);
        if (!TextUtils.isEmpty(lead)) {
            final TextView subtitle = new TextView(getContext());
            subtitle.setText(lead);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            subtitle.setTextColor(gray());
            subtitle.setMaxLines(3);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setLineSpacing(dp(1), 1f);
            box.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView read = new TextView(getContext());
        read.setText(LocaleController.getString(R.string.NilakReadArticle));
        read.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        read.setTypeface(TigiStyle.displayBold());
        read.setTextColor(0xFFFFFFFF);
        read.setGravity(Gravity.CENTER);
        read.setPadding(dp(18), 0, dp(18), 0);
        read.setBackground(TigiStyle.primaryPill(17));
        row.addView(read, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        final TextView size = new TextView(getContext());
        // reading time (and how far the person got) rather than the file size when known
        final int minutes = TigiArticle.minutesOf(message);
        final float done = TigiReadingProgress.fraction(fragment.getCurrentAccount(), message);
        final StringBuilder info = new StringBuilder(minutes > 0
            ? LocaleController.formatString(R.string.NilakReadMinutes, minutes)
            : AndroidUtilities.formatFileSize(message.getDocument().size));
        if (done > 0.03f && done < 0.97f) {
            info.append(" · ").append(LocaleController.formatString(R.string.NilakReadPercent, Math.round(done * 100)));
        }
        size.setText(info);
        size.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        size.setTextColor(gray());
        row.addView(size, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12, 0, 0, 0));
        box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        box.setContentDescription(LocaleController.getString(R.string.NilakArticleLabel) + ", " + title.getText());
        box.setOnClickListener(v -> TigiArticleReaderActivity.open(fragment, message));
        addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // small articles download ahead on Wi-Fi, so they open at once
        if (TigiDataSaverActivity.prefetchAllowed() && message.getDocument().size < 20 * 1024 * 1024) {
            FileLoader.getInstance(fragment.getCurrentAccount()).loadFile(message.getDocument(), message, FileLoader.PRIORITY_LOW, 0);
        }
    }

    /** Telegram's file colours by type: PDF red, documents blue, sheets green, archives orange, media violet. */
    static int documentColor(String extension) {
        switch (extension) {
            case "PDF":
                return 0xFFE5484D;
            case "DOC": case "DOCX": case "TXT": case "RTF": case "ODT": case "PAGE":
                return 0xFF3B82F6;
            case "XLS": case "XLSX": case "CSV": case "ODS": case "NUMB":
                return 0xFF22A55B;
            case "PPT": case "PPTX": case "KEY": case "ODP":
                return 0xFFF97316;
            case "ZIP": case "RAR": case "7Z": case "TAR": case "GZ":
                return 0xFFE8A33D;
            case "APK": case "XAPK":
                return 0xFF3DDC84;
            case "MP4": case "MKV": case "AVI": case "MOV": case "WEBM": case "MP3": case "M4A": case "FLAC": case "OGG": case "WAV":
                return 0xFF8B5CF6;
            default:
                return TigiStyle.accent();
        }
    }

    /** A file: a preview on top when there is one (the sender's thumbnail, or a PDF's first page once downloaded). */
    private void buildDocument() {
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(null);
        final TLRPC.Document document = message.getDocument();
        final String name = FileLoader.getDocumentFileName(document);
        final boolean pdf = TigiPdf.isPdf(document);
        final LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(box());
        final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
        final boolean showPreview = pdf || thumb != null && !(thumb instanceof TLRPC.TL_photoStrippedSize) && !(thumb instanceof TLRPC.TL_photoPathSize);
        if (showPreview) {
            documentPreview = new BackupImageView(getContext());
            documentPreview.setRoundRadius(dp(14), dp(14), 0, 0);
            documentPreview.getImageReceiver().setAspectFit(false);
            documentPreview.setBackgroundColor(pdf ? 0xFFFFFFFF : TigiLilac.inset());
            if (thumb != null) {
                documentPreview.setImage(ImageLocation.getForDocument(thumb, document), "640_360", (android.graphics.drawable.Drawable) null, message);
            }
            card.addView(documentPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, pdf ? 220 : 160));
            if (thumb == null) {
                documentPreview.setVisibility(GONE); // a PDF without a thumbnail: its own first page once it is here
            }
            loadPdfPreview();
        }
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(14), dp(8));
        final TextView icon = new TextView(getContext());
        String extension = "";
        if (name != null && name.lastIndexOf('.') > 0) {
            extension = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.US);
            if (extension.length() > 4) {
                extension = extension.substring(0, 4);
            }
        }
        if (pdf) {
            extension = "PDF";
        }
        icon.setText(extension.isEmpty() ? "FILE" : extension);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        icon.setTypeface(TigiStyle.displayExtraBold());
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Theme.createRoundRectDrawable(dp(10), documentColor(extension)));
        row.addView(icon, LayoutHelper.createLinear(44, 44));
        final LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        final TextView title = new TextView(getContext());
        title.setText(TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AttachDocument) : name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(TigiStyle.displayBold());
        title.setTextColor(text());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        documentStatus = new TextView(getContext());
        documentStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        documentStatus.setTextColor(gray());
        texts.addView(title);
        texts.addView(documentStatus);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        card.addView(row);
        // a thin line under the row while the file downloads
        downloadBar = new View(getContext()) {
            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                final Object tag = getTag();
                final float value = tag instanceof Float ? (Float) tag : 0f;
                canvas.drawColor(Theme.multAlpha(gray(), 0.25f));
                final android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(TigiStyle.accent());
                canvas.drawRect(0, 0, getWidth() * value, getHeight(), paint);
            }
        };
        downloadBar.setVisibility(GONE);
        card.addView(downloadBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3, 14, 0, 14, 10));
        card.setOnClickListener(v -> openDocument());
        card.setContentDescription(title.getText());
        addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateDocumentStatus(FileLoader.getInstance(fragment.getCurrentAccount()).isLoadingFile(FileLoader.getAttachFileName(document)) ? 0f : -1f);
    }

    /** A downloaded PDF's first page as the preview (rendered once, off the main thread, and cached). */
    private void loadPdfPreview() {
        if (documentPreview == null || message == null || !TigiPdf.isPdf(message.getDocument())) {
            return;
        }
        final File file = documentFile();
        if (file == null) {
            return;
        }
        final MessageObject bound = message;
        final int width = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) - dp(24);
        // the top of the first page (title, first lines), cut to the preview's shape instead of its middle
        final float shape = dp(220) / (float) Math.max(1, width);
        TigiPdf.firstPage(file, width, shape, preview -> {
            if (preview == null || bound != message || documentPreview == null) {
                return;
            }
            documentPreview.setImageBitmap(preview.bitmap);
            documentPreview.setVisibility(VISIBLE);
            pageCount = preview.pages;
            updateDocumentStatus(-1f);
        });
    }

    private File documentFile() {
        if (message == null || fragment == null) {
            return null;
        }
        File file = FileLoader.getInstance(fragment.getCurrentAccount()).getPathToMessage(message.messageOwner);
        if (file != null && file.exists()) {
            return file;
        }
        if (!TextUtils.isEmpty(message.messageOwner.attachPath)) {
            file = new File(message.messageOwner.attachPath);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    /** progress: -1 idle, 0..1 downloading. */
    private void updateDocumentStatus(float progress) {
        if (documentStatus == null || message == null) {
            return;
        }
        final long total = message.getDocument().size;
        String size = AndroidUtilities.formatFileSize(total);
        if (pageCount > 0) {
            size = LocaleController.formatPluralString("NilakPdfPages", pageCount) + " · " + size;
        }
        final boolean downloading = documentFile() == null && progress >= 0;
        if (documentFile() != null) {
            documentStatus.setText(size + " · " + LocaleController.getString(R.string.NilakTapToOpen));
        } else if (downloading) {
            documentStatus.setText(AndroidUtilities.formatFileSize((long) (total * progress)) + " / " + AndroidUtilities.formatFileSize(total) + " · " + LocaleController.getString(R.string.NilakDownloading));
        } else {
            documentStatus.setText(size);
        }
        if (downloadBar != null) {
            downloadBar.setVisibility(downloading ? VISIBLE : GONE);
            downloadBar.setTag(Math.max(0.02f, progress));
            downloadBar.invalidate();
        }
    }

    /** Downloads of this card's file: progress in the status line; when it lands, the preview and (if asked) open. */
    private void onFileEvent(int id, Object... args) {
        if (kind != KIND_DOCUMENT || message == null || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        if (!args[0].equals(FileLoader.getAttachFileName(message.getDocument()))) {
            return;
        }
        if (id == NotificationCenter.fileLoadProgressChanged) {
            final long loaded = args.length > 1 && args[1] instanceof Long ? (Long) args[1] : 0;
            final long total = args.length > 2 && args[2] instanceof Long ? (Long) args[2] : 0;
            updateDocumentStatus(total > 0 ? Math.min(1f, loaded / (float) total) : 0f);
        } else if (id == NotificationCenter.fileLoaded) {
            updateDocumentStatus(-1f);
            loadPdfPreview();
            if (openWhenDownloaded) {
                openWhenDownloaded = false;
                openDocument();
            }
        } else {
            openWhenDownloaded = false;
            updateDocumentStatus(-1f);
        }
    }

    /** PDFs open in Nilak's reader; other files in the app the phone uses for them. Not here yet: download, then open. */
    private void openDocument() {
        if (message == null || fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final File file = documentFile();
        if (file != null) {
            if (TigiPdf.isPdf(message.getDocument())) {
                fragment.presentFragment(new TigiPdfViewerActivity(message, file));
            } else {
                AndroidUtilities.openForView(message, fragment.getParentActivity(), fragment.getResourceProvider(), false);
            }
            return;
        }
        final FileLoader loader = FileLoader.getInstance(fragment.getCurrentAccount());
        if (loader.isLoadingFile(FileLoader.getAttachFileName(message.getDocument()))) {
            // a second tap while it downloads stops it
            loader.cancelLoadFile(message.getDocument());
            openWhenDownloaded = false;
            updateDocumentStatus(-1f);
            return;
        }
        openWhenDownloaded = true;
        loader.loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        updateDocumentStatus(0f);
    }

    /* link previews */

    private void buildLink() {
        setPadding(dp(12), dp(6), dp(12), dp(10));
        setBackground(null);
        final TLRPC.TL_webPage page = (TLRPC.TL_webPage) ((TLRPC.TL_messageMediaWebPage) MessageObject.getMedia(message.messageOwner)).webpage;
        final LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(VERTICAL);
        card.setBackground(box());
        if (page.photo != null && !page.photo.sizes.isEmpty()) {
            final BackupImageView image = new BackupImageView(getContext());
            image.setRoundRadius(dp(14), dp(14), 0, 0);
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(page.photo.sizes, AndroidUtilities.getPhotoSize());
            image.setImage(ImageLocation.getForPhoto(size, page.photo), "600_300", (android.graphics.drawable.Drawable) null, page);
            image.getImageReceiver().setAspectFit(false);
            card.addView(image, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160));
        }
        final LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        texts.setPadding(dp(12), dp(8), dp(12), dp(10));
        if (!TextUtils.isEmpty(page.site_name)) {
            final TextView site = new TextView(getContext());
            site.setText(page.site_name);
            site.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            site.setTypeface(TigiStyle.displayBold());
            site.setTextColor(TigiStyle.accent());
            texts.addView(site);
        }
        if (!TextUtils.isEmpty(page.title)) {
            final TextView title = new TextView(getContext());
            title.setText(page.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(TigiStyle.displayBold());
            title.setTextColor(text());
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
        }
        if (!TextUtils.isEmpty(page.description)) {
            final TextView description = new TextView(getContext());
            description.setText(page.description);
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            description.setTextColor(gray());
            description.setMaxLines(3);
            description.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(description);
        }
        card.addView(texts);
        card.setOnClickListener(v -> {
            if (fragment.getParentActivity() != null && !TextUtils.isEmpty(page.url)) {
                org.telegram.messenger.browser.Browser.openUrl(fragment.getParentActivity(), page.url);
            }
        });
        addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }
}
