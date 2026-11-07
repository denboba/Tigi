package org.nilak.writer;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.TigiArticle;
import org.telegram.ui.TigiArticleReaderActivity;

import java.io.File;

/** Preview: the draft written to a temporary article folder and shown in the article reader, as readers will see it. */
final class WriterPreview {

    private WriterPreview() {
    }

    static void open(BaseFragment fragment, WriterDoc doc) {
        try {
            final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nilak_writer_preview");
            TigiArticle.deleteRecursive(dir);
            dir.mkdirs();
            final File media = new File(dir, "media");
            String cover = null;
            for (WriterDoc.Block block : doc.blocks) {
                if ((block.type == WriterDoc.PHOTO || block.type == WriterDoc.VIDEO) && block.path != null && new File(block.path).exists()) {
                    media.mkdirs();
                    final File source = new File(block.path);
                    copy(source, new File(media, source.getName()));
                    if (cover == null && block.type == WriterDoc.PHOTO) {
                        cover = "media/" + source.getName();
                    }
                }
            }
            final TigiArticle.Meta meta = new TigiArticle.Meta();
            meta.title = doc.title.trim();
            meta.subtitle = "";
            final TLRPC.User me = UserConfig.getInstance(fragment.getCurrentAccount()).getCurrentUser();
            meta.author = me != null ? UserObject.getUserName(me) : "";
            if (cover == null) {
                final String made = WriterExport.makeCover(fragment.getCurrentAccount(), doc, media);
                cover = made != null ? "media/" + made : null;
            }
            meta.cover = cover;
            meta.words = doc.wordCount();
            meta.minutes = doc.minutes();
            meta.created = System.currentTimeMillis() / 1000;
            TigiArticle.writeMeta(dir, meta);
            TigiArticle.writeText(new File(dir, TigiArticle.BODY), WriterExport.markdown(doc));
            TigiArticleReaderActivity.preview(fragment, dir);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(from); java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            final byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }
}
