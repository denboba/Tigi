package org.nilak.writer;

import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

/**
 * Telegram stickers and GIFs inside an article. They are not copied into the .nilak file: the article keeps the
 * Telegram document itself (as "tgdoc:" + base64), and readers load it from Telegram's cache like a sticker in a chat.
 */
public final class WriterMedia {

    public static final String PREFIX = "tgdoc:";

    private WriterMedia() {
    }

    public static boolean isDocRef(String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    public static String encode(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        try {
            final SerializedData data = new SerializedData(document.getObjectSize());
            document.serializeToStream(data);
            final String ref = PREFIX + Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            data.cleanup();
            return ref;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static TLRPC.Document decode(String ref) {
        if (!isDocRef(ref)) {
            return null;
        }
        try {
            final byte[] bytes = Base64.decode(ref.substring(PREFIX.length()), Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
            final SerializedData data = new SerializedData(bytes);
            final TLRPC.Document document = TLRPC.Document.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            return document instanceof TLRPC.TL_documentEmpty ? null : document;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static boolean isSticker(TLRPC.Document document) {
        return document != null && (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true) || MessageObject.isVideoStickerDocument(document));
    }

    /** What Telegram needs to refresh an old file reference: the sticker set, or the saved GIFs. */
    private static Object parentOf(TLRPC.Document document) {
        if (isSticker(document)) {
            final TLRPC.InputStickerSet set = MessageObject.getInputStickerSet(document);
            if (set != null) {
                return set;
            }
        }
        return "gif" + document.id;
    }

    /** Shows the sticker (animated when it can be) or plays the GIF in the given image. */
    public static void bind(ImageReceiver receiver, TLRPC.Document document) {
        if (receiver == null || document == null) {
            return;
        }
        final Object parent = parentOf(document);
        final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 320);
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document) : null;
        if (isSticker(document)) {
            if (MessageObject.canAutoplayAnimatedSticker(document)) {
                receiver.setImage(ImageLocation.getForDocument(document), "240_240", thumbLocation, null, (String) null, parent, 1);
            } else {
                receiver.setImage(ImageLocation.getForDocument(document), "240_240", null, "webp", parent, 1);
            }
        } else {
            final String ext = !TextUtils.isEmpty(document.mime_type) && document.mime_type.startsWith("video/") ? "mp4" : null;
            receiver.setImage(ImageLocation.getForDocument(document), ImageLoader.AUTOPLAY_FILTER, thumbLocation, "90_90_b", document.size, ext, parent, 0);
        }
    }

    /** Width / height of the GIF, from its attributes (16:9 when unknown). */
    public static float aspect(TLRPC.Document document) {
        if (document != null) {
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo || attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                    if (attribute.w > 0 && attribute.h > 0) {
                        return attribute.w / (float) attribute.h;
                    }
                }
            }
        }
        return 16f / 9f;
    }
}
