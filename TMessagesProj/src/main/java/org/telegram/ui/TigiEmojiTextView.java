package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.widget.TextView;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedEmojiSpan;

/**
 * A TextView that also draws Telegram's custom (animated) emoji, so a post made only of such emoji does not show
 * as an empty card. Touch and links stay with whatever the caller sets.
 */
public class TigiEmojiTextView extends TextView {

    private AnimatedEmojiSpan.EmojiGroupedSpans stack;

    public TigiEmojiTextView(Context context) {
        super(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final Layout layout = getLayout();
        if (layout == null) {
            return;
        }
        try {
            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            stack = AnimatedEmojiSpan.update(AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, this, stack, layout);
            AnimatedEmojiSpan.drawAnimatedEmojis(canvas, layout, stack, 0, null, 0, 0, 0, 1f, null);
            canvas.restore();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AnimatedEmojiSpan.release(this, stack);
        stack = null;
    }
}
