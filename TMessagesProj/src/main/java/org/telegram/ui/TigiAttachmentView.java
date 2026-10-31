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
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
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
public class TigiAttachmentView extends LinearLayout {

    public static final int KIND_NONE = 0;
    public static final int KIND_POLL = 1;
    public static final int KIND_AUDIO = 2;
    public static final int KIND_DOCUMENT = 3;
    public static final int KIND_LINK = 4;

    private final BaseFragment fragment;
    private MessageObject message;
    private int kind;
    private final ArrayList<byte[]> pickedOptions = new ArrayList<>();
    private boolean voted;
    private ImageView playButton;
    private TextView documentStatus;
    private final Runnable playState = new Runnable() {
        @Override
        public void run() {
            updatePlayButton();
            if (isAttachedToWindow() && kind == KIND_AUDIO && message != null && MediaController.getInstance().isPlayingMessage(message)) {
                AndroidUtilities.runOnUIThread(this, 500);
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
        if (message.isMusic() || message.isVoice()) {
            return KIND_AUDIO;
        }
        if (media instanceof TLRPC.TL_messageMediaWebPage && ((TLRPC.TL_messageMediaWebPage) media).webpage instanceof TLRPC.TL_webPage) {
            final TLRPC.TL_webPage page = (TLRPC.TL_webPage) ((TLRPC.TL_messageMediaWebPage) media).webpage;
            return !TextUtils.isEmpty(page.title) || !TextUtils.isEmpty(page.description) ? KIND_LINK : KIND_NONE;
        }
        if (message.getDocument() != null && !message.isVideo() && !message.isGif() && !message.isSticker()
            && !message.isAnimatedSticker() && !message.isRoundVideo()) {
            return KIND_DOCUMENT;
        }
        return KIND_NONE;
    }

    /** Shows the message's attachment; returns false (and hides) when the card cannot show it. */
    public boolean bind(MessageObject message) {
        this.message = message;
        kind = kindOf(message);
        removeAllViews();
        playButton = null;
        documentStatus = null;
        switch (kind) {
            case KIND_POLL:
                buildPoll();
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
        return Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundGray));
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
        option.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray), Theme.multAlpha(TigiStyle.accent(), 0.18f)));
        option.setOnClickListener(v -> {
            if (poll.multiple_choice) {
                if (!removeOption(answer.option)) {
                    pickedOptions.add(answer.option);
                }
                option.setBackground(containsOption(answer.option) ? TigiStyle.tintedPill(12)
                    : Theme.createSimpleSelectorRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray), Theme.multAlpha(TigiStyle.accent(), 0.18f)));
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
        SendMessagesHelper.getInstance(fragment.getCurrentAccount()).sendVote(message, answers, null);
        bind(message);
    }

    private View resultRow(TLRPC.PollAnswer answer, int votes, int total, boolean chosen) {
        final int percent = total > 0 ? Math.round(votes * 100f / total) : 0;
        final FrameLayout row = new FrameLayout(getContext());
        row.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundGray)));
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

    /* music and voice */

    private void buildAudio() {
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(null);
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(14), dp(8));
        row.setBackground(box());
        playButton = new ImageView(getContext());
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setBackground(Theme.createRoundRectDrawable(dp(22), TigiStyle.accent()));
        playButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        playButton.setOnClickListener(v -> togglePlay());
        row.addView(playButton, LayoutHelper.createLinear(44, 44));
        final LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(VERTICAL);
        final TextView title = new TextView(getContext());
        final TextView subtitle = new TextView(getContext());
        if (message.isMusic()) {
            title.setText(message.getMusicTitle());
            subtitle.setText(message.getMusicAuthor() + " · " + AndroidUtilities.formatShortDuration((int) message.getDuration()));
        } else {
            title.setText(LocaleController.getString(R.string.AttachAudio));
            subtitle.setText(AndroidUtilities.formatShortDuration((int) message.getDuration()));
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
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        row.setOnClickListener(v -> togglePlay());
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updatePlayButton();
    }

    private void togglePlay() {
        if (message == null) {
            return;
        }
        final MediaController controller = MediaController.getInstance();
        if (controller.isPlayingMessage(message) && !controller.isMessagePaused()) {
            controller.pauseMessage(message);
        } else if (controller.isPlayingMessage(message)) {
            controller.playMessage(message);
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (kind == KIND_AUDIO) {
            AndroidUtilities.runOnUIThread(playState);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(playState);
    }

    /* documents */

    private void buildDocument() {
        setPadding(dp(12), dp(10), dp(12), dp(10));
        setBackground(null);
        final TLRPC.Document document = message.getDocument();
        final String name = FileLoader.getDocumentFileName(document);
        final LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(14), dp(8));
        row.setBackground(box());
        final TextView icon = new TextView(getContext());
        String extension = "";
        if (name != null && name.lastIndexOf('.') > 0) {
            extension = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.US);
            if (extension.length() > 4) {
                extension = extension.substring(0, 4);
            }
        }
        icon.setText(extension.isEmpty() ? "FILE" : extension);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        icon.setTypeface(TigiStyle.displayExtraBold());
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Theme.createRoundRectDrawable(dp(10), TigiStyle.accent()));
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
        row.setOnClickListener(v -> openDocument());
        addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateDocumentStatus(false);
    }

    private File documentFile() {
        final File file = FileLoader.getInstance(fragment.getCurrentAccount()).getPathToMessage(message.messageOwner);
        return file != null && file.exists() ? file : null;
    }

    private void updateDocumentStatus(boolean downloading) {
        if (documentStatus == null || message == null) {
            return;
        }
        final String size = AndroidUtilities.formatFileSize(message.getDocument().size);
        if (documentFile() != null) {
            documentStatus.setText(size + " · " + LocaleController.getString(R.string.NilakTapToOpen));
        } else {
            documentStatus.setText(size + (downloading ? " · " + LocaleController.getString(R.string.NilakDownloading) : ""));
        }
    }

    private void openDocument() {
        if (message == null || fragment.getParentActivity() == null) {
            return;
        }
        if (documentFile() != null) {
            AndroidUtilities.openForView(message, fragment.getParentActivity(), fragment.getResourceProvider(), false);
            return;
        }
        FileLoader.getInstance(fragment.getCurrentAccount()).loadFile(message.getDocument(), message, FileLoader.PRIORITY_NORMAL, 0);
        updateDocumentStatus(true);
        // check until the file is there, then it opens with the next tap
        final MessageObject watched = message;
        final Runnable[] check = new Runnable[1];
        final int[] tries = {0};
        check[0] = () -> {
            if (watched != message || !isAttachedToWindow()) {
                return;
            }
            updateDocumentStatus(documentFile() == null);
            if (documentFile() == null && ++tries[0] < 120) {
                AndroidUtilities.runOnUIThread(check[0], 1000);
            }
        };
        AndroidUtilities.runOnUIThread(check[0], 1000);
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
