package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;

import java.io.File;

/**
 * Nilak share sheet, built for posts to travel: send to friends inside the app, straight to WhatsApp, or to any
 * other app, with the photo or video itself attached when it is on the phone (so it plays in WhatsApp, Instagram
 * stories and the rest without a link), plus a short caption, the post link and "Shared from Nilak". Posts from
 * private channels have no link but still share as media.
 */
public final class TigiShare {

    private static final String WHATSAPP = "com.whatsapp";
    private static final int EXCERPT = 160;

    private TigiShare() {
    }

    public static void show(BaseFragment fragment, TLRPC.Chat chat, MessageObject primary, MessageObject media, CharSequence caption) {
        if (fragment == null || fragment.getParentActivity() == null || chat == null || primary == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final String link = publicLink(chat, primary);
        final File file = localFile(fragment.getCurrentAccount(), media != null ? media : primary);
        final String text = shareText(caption, link);
        if (link == null && file == null && TextUtils.isEmpty(caption)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.TigiSharePrivate)).show();
            return;
        }

        final BottomSheet sheet = new BottomSheet(context, false);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NilakShareTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(TigiStyle.displayExtraBold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 14));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(target(context, R.drawable.tigi_comment_24, LocaleController.getString(R.string.NilakShareFriends), () -> {
            sheet.dismiss();
            fragment.showDialog(ShareAlert.createShareAlert(context, primary, null, ChatObject.isChannelAndNotMegaGroup(chat), link, false));
        }), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (isInstalled(context, WHATSAPP)) {
            row.addView(target(context, R.drawable.tigi_share_24, "WhatsApp", () -> {
                sheet.dismiss();
                launch(fragment, buildIntent(file, media != null ? media : primary, text), WHATSAPP);
            }), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        if (link != null) {
            row.addView(target(context, R.drawable.msg_link, LocaleController.getString(R.string.CopyLink), () -> {
                sheet.dismiss();
                AndroidUtilities.addToClipboard(link);
                BulletinFactory.of(fragment).createCopyLinkBulletin().show();
            }), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        row.addView(target(context, R.drawable.msg_shareout, LocaleController.getString(R.string.NilakShareMore), () -> {
            sheet.dismiss();
            launch(fragment, buildIntent(file, media != null ? media : primary, text), null);
        }), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        content.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (file == null && (media != null && (media.isVideo() || media.isPhoto()))) {
            final TextView note = new TextView(context);
            note.setText(LocaleController.getString(R.string.NilakShareLinkOnly));
            note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            note.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
            content.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 12, 4, 0));
        }
        sheet.setCustomView(content);
        fragment.showDialog(sheet);
    }

    private static View target(Context context, int icon, String label, Runnable onClick) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(4), 0, dp(4));
        box.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setImageResource(icon);
        image.setColorFilter(new PorterDuffColorFilter(TigiStyle.accent(), PorterDuff.Mode.SRC_IN));
        image.setBackground(Theme.createCircleDrawable(dp(54), Theme.multAlpha(TigiStyle.accent(), 0.12f)));
        box.addView(image, LayoutHelper.createLinear(54, 54));
        final TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        text.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
        box.setContentDescription(label);
        box.setOnClickListener(v -> onClick.run());
        return box;
    }

    static String publicLink(TLRPC.Chat chat, MessageObject message) {
        final String username = ChatObject.getPublicUsername(chat);
        return TextUtils.isEmpty(username) ? null : "https://t.me/" + username + "/" + message.getId();
    }

    /** The first part of the caption, the link, and the app's name. */
    private static String shareText(CharSequence caption, String link) {
        final StringBuilder out = new StringBuilder();
        if (!TextUtils.isEmpty(caption)) {
            final String text = caption.toString().trim();
            out.append(text.length() > EXCERPT ? text.substring(0, EXCERPT).trim() + "…" : text);
        }
        if (link != null) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(link);
        }
        if (out.length() > 0) {
            out.append("\n");
        }
        out.append(LocaleController.getString(R.string.NilakSharedFrom));
        return out.toString();
    }

    /** The photo or video when it is already on the phone (downloaded, or the photo the feed showed). */
    private static File localFile(int account, MessageObject message) {
        if (message == null) {
            return null;
        }
        try {
            final FileLoader loader = FileLoader.getInstance(account);
            if (message.isVideo() || message.isGif()) {
                final File file = loader.getPathToMessage(message.messageOwner);
                return file != null && file.exists() && file.length() > 0 ? file : null;
            }
            if (message.isPhoto() && message.photoThumbs != null && !message.photoThumbs.isEmpty()) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    File file = loader.getPathToAttach(size, true);
                    if (file == null || !file.exists()) {
                        file = loader.getPathToAttach(size, false);
                    }
                    return file != null && file.exists() && file.length() > 0 ? file : null;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static Intent buildIntent(File file, MessageObject message, String text) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, text);
        if (file != null) {
            try {
                final Uri uri = FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", file);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setType(message != null && (message.isVideo() || message.isGif()) ? "video/mp4" : "image/jpeg");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                return intent;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        intent.setType("text/plain");
        return intent;
    }

    private static void launch(BaseFragment fragment, Intent intent, String packageName) {
        try {
            if (packageName != null) {
                intent.setPackage(packageName);
                fragment.getParentActivity().startActivity(intent);
            } else {
                fragment.getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.NilakShareTitle)));
            }
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NilakShareFailed)).show();
        }
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
