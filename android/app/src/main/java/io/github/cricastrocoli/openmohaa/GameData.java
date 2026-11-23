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

    // Imports the game from a file picked with the system picker: a .zip of the game's folder.
    public static ImportResult importFile(Context c, Uri file, Progress progress) {
        String name = displayName(c.getContentResolver(), file).toLowerCase();
        if (name.endsWith(".zip")) return importZip(c, file, progress);
        return new ImportResult(0, "unsupported");
    }

    // Copies the game's data folders out of a .zip of the game's install folder (or of its main
    // folder): files under main / mainta / maintt, whatever folders come before; or, with none
    // of those, the files beside pak0.pk3, as main. Runs on the calling thread.
    public static ImportResult importZip(Context c, Uri zip, Progress progress) {
        ContentResolver cr = c.getContentResolver();
        // ZipFile needs a path: the picked file's descriptor gives one.
        try (android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(zip, "r");
             ZipFile z = new ZipFile("/proc/self/fd/" + pfd.getFd())) {
            List<ZipEntry> entries = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.isDirectory()) entries.add(en);
            }
            // Where each file goes: "main/..." etc. under the game folder.
            List<String> targets = new ArrayList<>();
            boolean found = false;
            for (ZipEntry en : entries) {
                String t = placeInGame(en.getName());
                targets.add(t);
                if (t != null) found = true;
            }
            if (!found) {
                String mainDir = null;
                for (ZipEntry en : entries) {
                    String n = en.getName().replace('\\', '/');
                    if (n.toLowerCase().endsWith("pak0.pk3") && (n.length() == 8 || n.charAt(n.length() - 9) == '/')) {
                        mainDir = n.substring(0, n.length() - 8);
                        break;
                    }
                }
                if (mainDir == null) return new ImportResult(0, "nofiles");
                for (int i = 0; i < entries.size(); i++) {
                    String n = entries.get(i).getName().replace('\\', '/');
                    targets.set(i, n.startsWith(mainDir) ? "main/" + n.substring(mainDir.length()) : null);
                }
            }
            long total = 0;
            java.util.Set<String> games = new java.util.HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                String t = targets.get(i);
                if (t == null) continue;
                total += Math.max(0, entries.get(i).getSize());
                games.add(t.substring(0, t.indexOf('/')));
            }
            File game = gameDir(c);
            long done = 0;
            byte[] buf = new byte[1 << 20];
            for (int i = 0; i < entries.size(); i++) {
                String t = targets.get(i);
                if (t == null) continue;
                ZipEntry en = entries.get(i);
                File target = new File(game, t);
                String shown = t.substring(t.lastIndexOf('/') + 1);
                if (target.isFile() && target.length() == en.getSize()) {
                    done += en.getSize();
                    progress.update(done, total, shown);
                    continue;
                }
                File dir = target.getParentFile();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
                File part = new File(dir, target.getName() + ".part");
                try (InputStream in = z.getInputStream(en); OutputStream out = new FileOutputStream(part)) {
                    int n;
                    long lastReport = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - lastReport > (4 << 20)) {
                            progress.update(done, total, shown);
                            lastReport = done;
                        }
                    }
                }
                if (target.exists() && !target.delete()) throw new IOException("Can't replace " + target);
                if (!part.renameTo(target)) throw new IOException("Can't write " + target);
                progress.update(done, total, shown);
            }
            return new ImportResult(games.size(), null);
        } catch (IOException | RuntimeException e) {
            return new ImportResult(0, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    // "Some/Folder/MAIN/pak0.pk3" -> "main/pak0.pk3"; null when not under a game data folder.
    static String placeInGame(String entry) {
        String[] seg = entry.replace('\\', '/').split("/");
        for (int i = 0; i < seg.length - 1; i++) {
            for (String f : FOLDERS) {
                if (seg[i].equalsIgnoreCase(f)) {
                    String rest = join(seg, i + 1);
                    return rest.isEmpty() ? null : f + "/" + rest;
                }
            }
        }
        return null;
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

    // ---- Mods ------------------------------------------------------------------------------
    //
    // Mods are .pk3 files in the player's folder for each game (home/main, home/mainta,
    // home/maintt), which the engine reads after, and so over, the game's own. A mod that is
    // switched off is renamed to .pk3.off.

    public static final String OFF = ".off";

    public static File modsDir(Context c, int game) {
        return new File(new File(c.getExternalFilesDir(null), "home"), FOLDERS[game]);
    }

    public static List<File> mods(Context c, int game) {
        List<File> r = new ArrayList<>();
        File[] files = modsDir(c, game).listFiles();
        if (files == null) return r;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && (n.endsWith(".pk3") || n.endsWith(".pk3" + OFF))) r.add(f);
        }
        r.sort((a, b) -> modName(a).compareToIgnoreCase(modName(b)));
        return r;
    }

    public static boolean modOn(File f) {
        return !f.getName().endsWith(OFF);
    }

    public static String modName(File f) {
        String n = f.getName();
        return modOn(f) ? n : n.substring(0, n.length() - OFF.length());
    }

    public static boolean setModOn(File f, boolean on) {
        if (modOn(f) == on) return true;
        File to = new File(f.getParentFile(), on ? modName(f) : f.getName() + OFF);
        return f.renameTo(to);
    }

    // Top-level folders of the game's data: a zip whose files sit under one of these (at its
    // root or below some folder of its own) is a mod made of loose files.
    private static final String[] ASSET_DIRS = {"global", "textures", "sound", "music", "maps", "models",
            "ui", "scripts", "fonts", "gfx", "ubersound", "env", "video", "anims", "static", "sprites",
            "effects", "skins", "players"};

    // Installs mods picked with the system file picker. A .pk3 goes in as it is. From a .zip:
    // the .pk3 files inside it; or, when it has none, its game files packed into a .pk3. In a
    // zip, files under main / mainta / maintt go to that game, whatever folders come before
    // ("MyMod/main/global/..."); others go to the chosen game, from their game data folder on
    // ("MyMod/global/..." becomes "global/..."); anything else (a readme) is left out.
    // Returns the number of mods installed, or -1 with nothing usable.
    public static int importMods(Context c, List<Uri> uris, int game) throws IOException {
        ContentResolver cr = c.getContentResolver();
        int count = 0;
        byte[] buf = new byte[1 << 20];
        for (Uri u : uris) {
            String name = displayName(cr, u);
            String lower = name.toLowerCase();
            if (lower.endsWith(".pk3")) {
                try (InputStream in = cr.openInputStream(u)) {
                    copy(in, new File(modsDir(c, game, true), name), buf);
                }
                count++;
            } else if (lower.endsWith(".zip")) {
                count += importModZip(c, cr, u, name.substring(0, name.length() - 4), game, buf);
            }
        }
        return count == 0 ? -1 : count;
    }

    private static File modsDir(Context c, int game, boolean create) throws IOException {
        File dir = modsDir(c, game);
        if (create && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
        return dir;
    }

    // Where a file in a mod zip belongs: {game, path inside the game's data}, or null.
    private static Object[] placeInMod(String entry, int game) {
        String[] seg = entry.replace('\\', '/').split("/");
        for (int i = 0; i < seg.length - 1; i++) {
            for (int g = 0; g < FOLDERS.length; g++) {
                if (seg[i].equalsIgnoreCase(FOLDERS[g])) return new Object[]{g, join(seg, i + 1)};
            }
        }
        for (int i = 0; i < seg.length - 1; i++) {
            for (String a : ASSET_DIRS) if (seg[i].equalsIgnoreCase(a)) return new Object[]{game, join(seg, i)};
        }
        return null;
    }

    private static String join(String[] seg, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < seg.length; i++) {
            if (seg[i].isEmpty()) continue;
            if (b.length() > 0) b.append('/');
            b.append(seg[i]);
        }
        return b.toString();
    }

    private static int importModZip(Context c, ContentResolver cr, Uri u, String base, int game, byte[] buf)
            throws IOException {
        // First look: .pk3 files inside, or game files.
        boolean pk3s = false, loose = false;
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(cr.openInputStream(u))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                if (e.getName().toLowerCase().endsWith(".pk3")) pk3s = true;
                else if (placeInMod(e.getName(), game) != null) loose = true;
            }
        }
        if (!pk3s && !loose) return 0;
        int count = 0;
        java.util.Map<Integer, java.util.zip.ZipOutputStream> packs = new java.util.HashMap<>();
        java.util.Map<Integer, File> parts = new java.util.HashMap<>();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(cr.openInputStream(u))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String en = e.getName().replace('\\', '/');
                if (pk3s) {
                    if (!en.toLowerCase().endsWith(".pk3")) continue;
                    Object[] p = placeInMod(en, game);
                    int g = p != null ? (Integer) p[0] : game;
                    copy(zin, new File(modsDir(c, g, true), en.substring(en.lastIndexOf('/') + 1)), buf);
                    count++;
                    continue;
                }
                Object[] p = placeInMod(en, game);
                if (p == null) continue;
                int g = (Integer) p[0];
                java.util.zip.ZipOutputStream out = packs.get(g);
                if (out == null) {
                    File part = new File(modsDir(c, g, true), base + ".pk3.part");
                    parts.put(g, part);
                    out = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(part)));
                    packs.put(g, out);
                }
                out.putNextEntry(new ZipEntry((String) p[1]));
                int n;
                while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                out.closeEntry();
            }
        } finally {
            for (java.util.zip.ZipOutputStream out : packs.values()) out.close();
        }
        for (java.util.Map.Entry<Integer, File> pe : parts.entrySet()) {
            File part = pe.getValue();
            File to = new File(part.getParentFile(), base + ".pk3");
            if (to.exists() && !to.delete()) throw new IOException("Can't replace " + to);
            if (!part.renameTo(to)) throw new IOException("Can't write " + to);
            count++;
        }
        return count;
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor cur = cr.query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        }
        String p = u.getLastPathSegment();
        return p != null ? p.substring(p.lastIndexOf('/') + 1) : "mod.pk3";
    }

    // Copies without closing the input (it may be a zip entry); writes through a .part file.
    private static void copy(InputStream in, File to, byte[] buf) throws IOException {
        File part = new File(to.getParentFile(), to.getName() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (to.exists() && !to.delete()) throw new IOException("Can't replace " + to);
        if (!part.renameTo(to)) throw new IOException("Can't write " + to);
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
