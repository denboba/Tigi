package org.telegram.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * One post on its own page, like a post opened on Instagram: the media on top, the whole text under it (never cut),
 * then likes, comments and sharing. Tapping the media here opens it full screen.
 */
public class TigiPostActivity extends TigiFeedBase {

    private final FeedPost post;

    public TigiPostActivity(FeedPost post) {
        super(null);
        this.post = post;
    }

    @Override
    public boolean onFragmentCreate() {
        if (post == null || post.messages.isEmpty()) {
            return false;
        }
        posts.clear();
        posts.add(post);
        return super.onFragmentCreate();
    }

    @Override
    protected boolean expandsCaptions() {
        return true;
    }

    @Override
    protected boolean isLoadingPosts() {
        return false;
    }

    @Override
    protected boolean isInitialLoading() {
        return false;
    }

    @Override
    protected View createHeaderView(Context context) {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (TigiLilac.on()) {
            header.setBackground(TigiLilac.pageBar());
        } else {
            header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        applyTopInset(header);
        final int ink = TigiLilac.on() ? TigiLilac.ink() : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(ink);
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> finishFragment());
        if (LocaleController.isRTL) {
            back.setScaleX(-1f);
        }
        header.addView(back, LayoutHelper.createLinear(48, 56));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakPost));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(ink);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 16, 0));
        return header;
    }
}
