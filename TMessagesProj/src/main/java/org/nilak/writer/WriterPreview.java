package org.nilak.writer;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiArticleReaderActivity;
import org.telegram.ui.TigiRichArticle;

import java.io.File;

/** Preview: the draft written to a temporary article folder and shown in the article reader, as readers will see it. */
final class WriterPreview {

    private WriterPreview() {
    }

    static void open(BaseFragment fragment, WriterDoc doc) {
        try {
            final TigiRichArticle.Converted converted = TigiRichArticle.convert(WriterBridge.toBlocks(doc, false), (id, video) -> null);
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_writer_preview");
            TigiArticle.deleteRecursive(dir);
            dir.mkdirs();
            final TigiArticle.Meta meta = new TigiArticle.Meta();
            meta.title = converted.title;
            meta.subtitle = converted.subtitle;
            final TLRPC.User me = UserConfig.getInstance(fragment.getCurrentAccount()).getCurrentUser();
            meta.author = me != null ? UserObject.getUserName(me) : "";
            meta.words = doc.wordCount();
            meta.minutes = doc.minutes();
            meta.created = System.currentTimeMillis() / 1000;
            TigiArticle.writeMeta(dir, meta);
            TigiArticle.writeText(new File(dir, TigiArticle.BODY), converted.body.toString());
            TigiArticleReaderActivity.preview(fragment, dir);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
