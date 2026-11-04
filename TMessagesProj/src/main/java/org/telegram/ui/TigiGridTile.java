package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/**
 * Nilak: one tile of a profile grid, drawn for whatever the post is: photo, video (tall or square), GIF, album,
 * sticker or big emoji, text, article, music, voice, PDF or other file, poll, link, round video. The parent row sizes
 * it; the tile only draws. Stickers show their first frame, so a grid full of them stays light.
 */
public class TigiGridTile extends FrameLayout {

    private final BackupImageView imageView;
    private final TextView topRight;
    private final TextView topLeft;
    private final TextView bottomLeft;
    private final TextView body;
    private final LinearLayout column;
    private final WaveView wave;
    private MessageObject message;

    public TigiGridTile(Context context) {
        super(context);
        imageView = new BackupImageView(context);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(9), dp(9), dp(9), dp(9));
        wave = new WaveView(context);
        column.addView(wave, LayoutHelper.createLinear(64, 32, Gravity.CENTER_HORIZONTAL));
        body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(dp(1), 1f);
        column.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        topRight = badge(context);
        addView(topRight, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 5, 5, 0));
        topLeft = badge(context);
        addView(topLeft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 5, 5, 0, 0));
        bottomLeft = new TextView(context);
        bottomLeft.setTextColor(0xFFFFFFFF);
        bottomLeft.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        bottomLeft.setTypeface(AndroidUtilities.bold());
        bottomLeft.setShadowLayer(dp(2), 0, 0, 0x99000000);
        addView(bottomLeft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 0, 5));
    }

    private static TextView badge(Context context) {
        final TextView view = new TextView(context);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        view.setTypeface(AndroidUtilities.bold());
        view.setPadding(dp(5), dp(1), dp(5), dp(1));
        view.setBackground(Theme.createRoundRectDrawable(dp(5), 0x73000000));
        return view;
    }

    public MessageObject getMessage() {
        return message;
    }

    private void reset() {
        imageView.setVisibility(VISIBLE);
        imageView.setImageDrawable(null);
        imageView.setColorFilter(null);
        imageView.setRoundRadius(0);
        imageView.getImageReceiver().setAspectFit(false);
        imageView.getImageReceiver().setAllowStartLottieAnimation(true);
        imageView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.setVisibility(GONE);
        column.setGravity(Gravity.CENTER);
        wave.setVisibility(GONE);
        body.setMaxLines(6);
        body.setGravity(Gravity.CENTER);
        body.setTypeface(null);
        topRight.setVisibility(GONE);
        topLeft.setVisibility(GONE);
        bottomLeft.setVisibility(GONE);
        setBackground(null);
    }

    private int text() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int soft() {
        return Theme.isCurrentThemeDark() ? 0xFF16131B : 0xFFF4F1F8;
    }

    /** Shows the post; album marks a post that stands for several photos; tall is the two-row video tile. */
    public void bind(MessageObject message, int albumSize, boolean tall) {
        this.message = message;
        reset();
        if (message == null) {
            return;
        }
        final int kind = TigiAttachmentView.kindOf(message);
        if (TigiFeedBase.isCardMedia(message)) {
            bindVisual(message, albumSize, tall);
        } else if (kind == TigiAttachmentView.KIND_STICKER) {
            bindSticker(message);
        } else if (message.isRoundVideo()) {
            bindRound(message);
        } else if (kind == TigiAttachmentView.KIND_ARTICLE) {
            bindArticle(message);
        } else if (kind == TigiAttachmentView.KIND_AUDIO) {
            if (message.isMusic()) {
                bindMusic(message);
            } else {
                bindVoice(message);
            }
        } else if (kind == TigiAttachmentView.KIND_DOCUMENT) {
            bindDocument(message);
        } else if (kind == TigiAttachmentView.KIND_POLL) {
            bindPoll(message);
        } else if (kind == TigiAttachmentView.KIND_LINK) {
            bindLink(message);
        } else if (!TextUtils.isEmpty(message.messageOwner.message)) {
            bindText(message.messageOwner.message);
        } else {
            bindLabel(message);
        }
    }

    private void bindVisual(MessageObject message, int albumSize, boolean tall) {
        final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, tall ? 480 : 320);
        if (size != null) {
            imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), tall ? "240_480" : "200_200", (android.graphics.drawable.Drawable) null, message.photoThumbsObject);
        } else {
            imageView.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFF141018));
        }
        if (message.isGif()) {
            topLeft.setText("GIF");
            topLeft.setVisibility(VISIBLE);
        } else if (message.isVideo()) {
            topRight.setText(AndroidUtilities.formatShortDuration((int) message.getDuration()));
            topRight.setVisibility(VISIBLE);
            if (message.messageOwner.views > 0) {
                bottomLeft.setText("▶ " + LocaleController.formatShortNumber(message.messageOwner.views, null));
                bottomLeft.setVisibility(VISIBLE);
            }
        }
        if (albumSize > 1) {
            topRight.setText("▣ " + albumSize);
            topRight.setVisibility(VISIBLE);
        }
        setContentDescription(LocaleController.getString(message.isVideo() ? R.string.AttachVideo : message.isGif() ? R.string.AttachGif : R.string.AttachPhoto)
            + (albumSize > 1 ? ", " + LocaleController.getString(R.string.TigiProfileAlbum) : ""));
    }

    private void bindSticker(MessageObject message) {
        setBackgroundColor(soft());
        final TLRPC.Document document = message.getDocument();
        imageView.getImageReceiver().setAspectFit(true);
        // first frame only: a grid of moving stickers would be heavy and busy
        imageView.getImageReceiver().setAllowStartLottieAnimation(false);
        imageView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 14, 14, 14, 14));
        if (document != null) {
            if (MessageObject.isTextColorEmoji(document)) {
                imageView.setColorFilter(Theme.getAnimatedEmojiColorFilter(null));
            }
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 160);
            if (MessageObject.canAutoplayAnimatedSticker(document)) {
                imageView.getImageReceiver().setImage(ImageLocation.getForDocument(document), "120_120", ImageLocation.getForDocument(thumb, document), null, (String) null, message, 1);
            } else {
                imageView.getImageReceiver().setImage(ImageLocation.getForDocument(document), "120_120", null, "webp", message, 1);
            }
        } else if (message.messageText != null) {
            imageView.setVisibility(GONE);
            column.setVisibility(VISIBLE);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
            body.setText(org.telegram.messenger.Emoji.replaceEmoji(message.messageText.toString(), body.getPaint().getFontMetricsInt(), false));
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
        }
        setContentDescription(LocaleController.getString(R.string.AttachSticker));
    }

    private void bindRound(MessageObject message) {
        setBackgroundColor(soft());
        final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 320);
        imageView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 12, 12, 12, 12));
        imageView.setRoundRadius(dp(200));
        if (size != null) {
            imageView.setImage(ImageLocation.getForObject(size, message.photoThumbsObject), "200_200", (android.graphics.drawable.Drawable) null, message.photoThumbsObject);
        }
        topRight.setText(AndroidUtilities.formatShortDuration((int) message.getDuration()));
        topRight.setVisibility(VISIBLE);
        setContentDescription(LocaleController.getString(R.string.AttachRound));
    }

    private void bindText(String text) {
        imageView.setVisibility(GONE);
        column.setVisibility(VISIBLE);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{TigiStyle.accent(), Theme.adaptHSV(TigiStyle.accent(), 0.02f, -0.12f)}));
        body.setTextColor(0xFFFFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        body.setTypeface(TigiStyle.displayExtraBold());
        body.setText(text.trim());
        setContentDescription(text);
    }

    private void bindArticle(MessageObject message) {
        imageView.setVisibility(GONE);
        column.setVisibility(VISIBLE);
        column.setGravity(Gravity.BOTTOM | Gravity.START);
        setBackgroundColor(Theme.multAlpha(TigiStyle.accent(), Theme.isCurrentThemeDark() ? 0.22f : 0.10f));
        final String title = TigiArticle.titleOf(message);
        final android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
        line.append(LocaleController.getString(R.string.NilakArticleLabel).toUpperCase(Locale.getDefault())).append("\n");
        line.setSpan(new android.text.style.ForegroundColorSpan(TigiStyle.accent()), 0, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.RelativeSizeSpan(0.75f), 0, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append(title);
        body.setGravity(Gravity.START);
        body.setTextColor(text());
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        body.setTypeface(TigiStyle.displayBold());
        body.setText(line);
        setContentDescription(LocaleController.getString(R.string.NilakArticleLabel) + ", " + title);
    }

    private void bindMusic(MessageObject message) {
        setBackgroundColor(0xFF1B1424);
        final TLRPC.Document document = message.getDocument();
        final TLRPC.PhotoSize cover = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320) : null;
        if (cover != null) {
            imageView.setImage(ImageLocation.getForDocument(cover, document), "200_200", (android.graphics.drawable.Drawable) null, message);
            bottomLeft.setText("♪ " + message.getMusicTitle());
            bottomLeft.setVisibility(VISIBLE);
        } else {
            imageView.setVisibility(GONE);
            column.setVisibility(VISIBLE);
            body.setTextColor(0xFFFFFFFF);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            body.setMaxLines(3);
            body.setText("♪\n" + message.getMusicTitle() + "\n" + message.getMusicAuthor());
        }
        setContentDescription(LocaleController.getString(R.string.AttachMusic) + ", " + message.getMusicTitle());
    }

    private void bindVoice(MessageObject message) {
        imageView.setVisibility(GONE);
        column.setVisibility(VISIBLE);
        setBackgroundColor(soft());
        wave.setVisibility(VISIBLE);
        wave.seed = message.getId();
        wave.invalidate();
        body.setTextColor(text());
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        body.setTypeface(AndroidUtilities.bold());
        body.setText(AndroidUtilities.formatShortDuration((int) message.getDuration()));
        setContentDescription(LocaleController.getString(R.string.AttachAudio));
    }

    private void bindDocument(MessageObject message) {
        final TLRPC.Document document = message.getDocument();
        final String name = FileLoader.getDocumentFileName(document);
        final boolean pdf = TigiPdf.isPdf(document);
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320) : null;
        String extension = "FILE";
        if (pdf) {
            extension = "PDF";
        } else if (name != null && name.lastIndexOf('.') > 0) {
            extension = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.US);
            if (extension.length() > 4) {
                extension = extension.substring(0, 4);
            }
        }
        if (thumb != null && !(thumb instanceof TLRPC.TL_photoStrippedSize) && !(thumb instanceof TLRPC.TL_photoPathSize)) {
            setBackgroundColor(0xFFFFFFFF);
            imageView.setImage(ImageLocation.getForDocument(thumb, document), "200_200", (android.graphics.drawable.Drawable) null, message);
        } else {
            imageView.setVisibility(GONE);
            column.setVisibility(VISIBLE);
            column.setGravity(Gravity.TOP | Gravity.START);
            setBackgroundColor(soft());
            body.setGravity(Gravity.START);
            body.setTextColor(text());
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            body.setTypeface(AndroidUtilities.bold());
            body.setMaxLines(4);
            body.setText(TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AttachDocument) : name);
        }
        topLeft.setText(extension + (document != null ? " · " + AndroidUtilities.formatFileSize(document.size) : ""));
        topLeft.setBackground(Theme.createRoundRectDrawable(dp(5), TigiAttachmentView.documentColor(extension)));
        topLeft.setVisibility(VISIBLE);
        setContentDescription(TextUtils.isEmpty(name) ? LocaleController.getString(R.string.AttachDocument) : name);
    }

    private void bindPoll(MessageObject message) {
        imageView.setVisibility(GONE);
        column.setVisibility(VISIBLE);
        column.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setBackgroundColor(soft());
        final TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) MessageObject.getMedia(message.messageOwner);
        final String question = media.poll != null && media.poll.question != null ? media.poll.question.text : "";
        body.setGravity(Gravity.START);
        body.setTextColor(text());
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        body.setTypeface(AndroidUtilities.bold());
        body.setMaxLines(3);
        body.setText("📊 " + question);
        setContentDescription(LocaleController.getString(R.string.Poll) + ", " + question);
    }

    private void bindLink(MessageObject message) {
        final TLRPC.TL_webPage page = (TLRPC.TL_webPage) ((TLRPC.TL_messageMediaWebPage) MessageObject.getMedia(message.messageOwner)).webpage;
        if (page.photo != null && !page.photo.sizes.isEmpty()) {
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(page.photo.sizes, 320);
            imageView.setImage(ImageLocation.getForPhoto(size, page.photo), "200_200", (android.graphics.drawable.Drawable) null, page);
            topLeft.setText("🔗 " + (TextUtils.isEmpty(page.site_name) ? "" : page.site_name));
            topLeft.setVisibility(VISIBLE);
        } else {
            bindText(!TextUtils.isEmpty(message.messageOwner.message) ? message.messageOwner.message : page.title != null ? page.title : "");
        }
        setContentDescription(page.title != null ? page.title : page.url);
    }

    private void bindLabel(MessageObject message) {
        imageView.setVisibility(GONE);
        column.setVisibility(VISIBLE);
        setBackgroundColor(soft());
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        final TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        final int label = media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive || media instanceof TLRPC.TL_messageMediaVenue ? R.string.AttachLocation
            : media instanceof TLRPC.TL_messageMediaContact ? R.string.AttachContact : R.string.TigiPostUnsupported;
        body.setText(LocaleController.getString(label));
        setContentDescription(body.getText());
    }

    /** A few rounded bars that read as a voice message (stable per post). */
    private static class WaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        int seed;

        WaveView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(TigiStyle.accent());
            final int bars = 9;
            final float w = dp(3);
            final float gap = (getWidth() - bars * w) / (bars - 1);
            final java.util.Random random = new java.util.Random(seed);
            for (int i = 0; i < bars; i++) {
                final float h = getHeight() * (0.3f + 0.7f * random.nextFloat());
                final float x = i * (w + gap);
                rect.set(x, (getHeight() - h) / 2f, x + w, (getHeight() + h) / 2f);
                canvas.drawRoundRect(rect, w / 2f, w / 2f, paint);
            }
        }
    }
}
