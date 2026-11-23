package io.github.cricastrocoli.openmohaa;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// The game's files: where they go (Android/data/<app>/files/game, as the engine expects),
// what is there, importing them from a folder the player picks, and pictures from them.
public final class GameData {
    // Data folders of Allied Assault, Spearhead and Breakthrough, and a file each must have.
    public static final String[] FOLDERS = {"main", "mainta", "maintt"};
    private static final String[] REQUIRED = {"pak0.pk3", "pak1.pk3", "pak1.pk3"};

    private GameData() {}

    public static File gameDir(Context c) {
        return new File(c.getExternalFilesDir(null), "game");
    }

    // Whether each game's files are there.
    public static boolean[] installed(Context c) {
        boolean[] r = new boolean[FOLDERS.length];
        File game = gameDir(c);
        for (int i = 0; i < FOLDERS.length; i++) {
            File dir = new File(game, FOLDERS[i]);
            String[] names = dir.list();
            if (names == null) continue;
            for (String n : names) if (n.equalsIgnoreCase(REQUIRED[i])) r[i] = true;
        }
        return r;
    }

    public static long sizeOnDisk(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) s += sizeOnDisk(k);
        return s;
    }

    // ---- Import ---------------------------------------------------------------------------

    public interface Progress {
        void update(long done, long total, String file);
    }

    public static final class ImportResult {
        public final int folders;      // game data folders copied
        public final String error;     // null if it went well
        ImportResult(int folders, String error) { this.folders = folders; this.error = error; }
    }

    private static final class Doc {
        final String id, name;
        final boolean dir;
        final long size;
        Doc(String id, String name, boolean dir, long size) {
            this.id = id; this.name = name; this.dir = dir; this.size = size;
        }
    }

    // Copies the game's data folders from a folder picked with the system picker: the game's
    // install folder (holding main, mainta, maintt) or the main folder itself. Only those
    // folders are copied (not the Windows programs). Runs on the calling thread.
    public static ImportResult importFrom(Context c, Uri tree, Progress progress) {
        ContentResolver cr = c.getContentResolver();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            List<Doc> top = children(cr, tree, rootId);
            List<Doc[]> sources = new ArrayList<>();  // {source folder, target name}
            for (Doc d : top) {
                for (String f : FOLDERS) {
                    if (d.dir && d.name.equalsIgnoreCase(f)) sources.add(new Doc[]{d, new Doc(null, f, true, 0)});
                }
            }
            if (sources.isEmpty()) {
                // The picked folder may be main itself.
                for (Doc d : top) {
                    if (!d.dir && d.name.equalsIgnoreCase("pak0.pk3")) {
                        sources.add(new Doc[]{new Doc(rootId, "main", true, 0), new Doc(null, "main", true, 0)});
                        break;
                    }
                }
            }
            if (sources.isEmpty()) return new ImportResult(0, "nofiles");

            long total = 0;
            for (Doc[] s : sources) total += treeSize(cr, tree, s[0].id);
            long[] done = {0};
            File game = gameDir(c);
            for (Doc[] s : sources) copyTree(cr, tree, s[0].id, new File(game, s[1].name), done, total, progress);
            return new ImportResult(sources.size(), null);
        } catch (IOException | RuntimeException e) {
            return new ImportResult(0, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static List<Doc> children(ContentResolver cr, Uri tree, String parentId) {
        List<Doc> r = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cur = cr.query(uri, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2));
                r.add(new Doc(cur.getString(0), cur.getString(1), dir, cur.isNull(3) ? 0 : cur.getLong(3)));
            }
        }
        return r;
    }

    private static long treeSize(ContentResolver cr, Uri tree, String id) {
        long s = 0;
        for (Doc d : children(cr, tree, id)) s += d.dir ? treeSize(cr, tree, d.id) : d.size;
        return s;
    }

    private static void copyTree(ContentResolver cr, Uri tree, String id, File to, long[] done, long total,
                                 Progress progress) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Can't create " + to);
        byte[] buf = new byte[1 << 20];
        for (Doc d : children(cr, tree, id)) {
            File target = new File(to, d.name);
            if (d.dir) {
                copyTree(cr, tree, d.id, target, done, total, progress);
                continue;
            }
            if (target.isFile() && target.length() == d.size) {  // already there
                done[0] += d.size;
                progress.update(done[0], total, d.name);
                continue;
            }
            File part = new File(to, d.name + ".part");
            try (InputStream in = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id));
                 OutputStream out = new FileOutputStream(part)) {
                if (in == null) throw new IOException("Can't read " + d.name);
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done[0] += n;
                    if (done[0] - lastReport > (4 << 20)) {
                        progress.update(done[0], total, d.name);
                        lastReport = done[0];
                    }
                }
            }
            if (!part.renameTo(target)) throw new IOException("Can't write " + target);
            progress.update(done[0], total, d.name);
        }
    }

    // ---- Pictures --------------------------------------------------------------------------

    // A random mission briefing picture from the player's own Allied Assault files (main/
    // Pak1.pk3), for the launcher's background; null if there are none.
    public static Bitmap briefingPicture(Context c) {
        File main = new File(gameDir(c), "main");
        String[] names = main.list();
        if (names == null) return null;
        for (String n : names) {
            if (!n.equalsIgnoreCase("pak1.pk3")) continue;
            try (ZipFile zip = new ZipFile(new File(main, n))) {
                List<ZipEntry> pics = new ArrayList<>();
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry z = e.nextElement();
                    String path = z.getName().toLowerCase();
                    if (path.startsWith("textures/mohmenu/briefing/") && path.endsWith(".tga")) pics.add(z);
                }
                if (pics.isEmpty()) return null;
                ZipEntry pick = pics.get(new Random().nextInt(pics.size()));
                try (InputStream in = zip.getInputStream(pick)) {
                    return Tga.decode(in);
                }
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
