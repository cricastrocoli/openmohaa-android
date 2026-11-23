package io.github.cricastrocoli.openmohaa;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// Installs the game straight from its GOG.com installer (setup_….exe and its .bin parts), on the
// device, with innoextract (packaged as libinnoextract.so): only the game data folders (main,
// mainta, maintt) are taken out of it.
final class GogInstaller {
    static {
        System.loadLibrary("gogexec");
    }

    private static native int[] spawn(String[] argv, int[] fds);
    private static native int waitFor(int pid);

    private static final int FIRST_FD = 10;  // where gog_exec.c hands the files to the program
    private static final String[] FILTER = {"-I", "main", "-I", "mainta", "-I", "maintt"};

    private GogInstaller() {}

    // Whether files picked for import are an installer (an .exe among them).
    static boolean isInstaller(List<String> names) {
        for (String n : names) if (n.toLowerCase().endsWith(".exe")) return true;
        return false;
    }

    // Errors (GameData.ImportResult.error): "noexe", "nofiles" (not this game's installer),
    // "missing:<what innoextract said>" (a .bin part wasn't picked), "nospace:<bytes needed>",
    // or innoextract's own message.
    static GameData.ImportResult install(Context c, List<Uri> uris, List<String> names, GameData.Progress progress) {
        int exe = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).toLowerCase().endsWith(".exe")) { exe = i; break; }
        }
        if (exe < 0) return new GameData.ImportResult(0, "noexe");

        ContentResolver cr = c.getContentResolver();
        List<ParcelFileDescriptor> open = new ArrayList<>();
        File links = new File(c.getCacheDir(), "gog-installer");
        File game = GameData.gameDir(c);
        File out = new File(game, ".gog-import");
        try {
            // The installer's files, open, and links to them under their own names.
            deleteTree(links);
            if (!links.mkdirs()) throw new IOException("Can't create " + links);
            List<Integer> fds = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                String n = names.get(i).toLowerCase();
                if (i != exe && !n.endsWith(".bin")) continue;  // only the installer's own files
                ParcelFileDescriptor pfd = cr.openFileDescriptor(uris.get(i), "r");
                if (pfd == null) throw new IOException("Can't read " + names.get(i));
                open.add(pfd);
                Os.symlink("/proc/self/fd/" + (FIRST_FD + fds.size()), new File(links, names.get(i)).getPath());
                fds.add(pfd.getFd());
            }
            int[] fdArray = new int[fds.size()];
            for (int i = 0; i < fdArray.length; i++) fdArray[i] = fds.get(i);
            String setup = new File(links, names.get(exe)).getPath();
            String program = new File(c.getApplicationInfo().nativeLibraryDir, "libinnoextract.so").getPath();

            // What there is to take out, and its size.
            List<String> args = new ArrayList<>();
            args.add(program);
            args.add("--list-sizes");
            args.add("-s");
            for (String f : FILTER) args.add(f);
            args.add(setup);
            List<String> lines = new ArrayList<>();
            int rc = run(args, fdArray, lines::add);
            if (rc != 0) return new GameData.ImportResult(0, failure(lines, rc));
            long total = 0;
            int files = 0;
            for (String l : lines) {
                int sp = l.indexOf(' ');
                if (sp <= 0) continue;
                try {
                    total += Long.parseLong(l.substring(0, sp));
                    files++;
                } catch (NumberFormatException ignored) {
                }
            }
            if (files == 0) return new GameData.ImportResult(0, "nofiles");
            if (!game.isDirectory() && !game.mkdirs()) throw new IOException("Can't create " + game);
            long free = new StatFs(game.getPath()).getAvailableBytes();
            if (free < total + (64L << 20)) return new GameData.ImportResult(0, "nospace:" + total);

            // Take it out, into a folder of its own, watching how much is written.
            deleteTree(out);
            if (!out.mkdirs()) throw new IOException("Can't create " + out);
            args.clear();
            args.add(program);
            args.add("-e");
            args.add("-p");
            args.add("0");
            args.add("-c");
            args.add("0");
            args.add("-d");
            args.add(out.getPath());
            for (String f : FILTER) args.add(f);
            args.add(setup);
            ArrayDeque<String> tail = new ArrayDeque<>();
            String[] current = {""};
            long finalTotal = total;
            boolean[] running = {true};
            Thread watch = new Thread(() -> {
                while (running[0]) {
                    progress.update(Math.min(GameData.sizeOnDisk(out), finalTotal), finalTotal, current[0]);
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "gog-progress");
            watch.start();
            try {
                rc = run(args, fdArray, l -> {
                    // ' - "app/main/Pak0.pk3"' as each file is taken out.
                    int q = l.indexOf('"'), e = l.lastIndexOf('"');
                    if (l.startsWith(" - ") && q >= 0 && e > q) {
                        String p = l.substring(q + 1, e);
                        current[0] = p.substring(p.lastIndexOf('/') + 1);
                    }
                    tail.add(l);
                    if (tail.size() > 8) tail.removeFirst();
                });
            } finally {
                running[0] = false;
                watch.interrupt();
            }
            if (rc != 0) {
                deleteTree(out);
                return new GameData.ImportResult(0, failure(new ArrayList<>(tail), rc));
            }

            // Into place: each data folder found in what came out goes to the game folder.
            int moved = 0;
            for (String folder : GameData.FOLDERS) {
                File from = find(out, folder);
                if (from == null) continue;
                moveTree(from, new File(game, folder));
                moved++;
            }
            deleteTree(out);
            progress.update(total, total, "");
            return moved == 0 ? new GameData.ImportResult(0, "nofiles") : new GameData.ImportResult(moved, null);
        } catch (Exception e) {
            android.util.Log.e("MOHAA", "GOG install failed", e);
            deleteTree(out);
            return new GameData.ImportResult(0, e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            for (ParcelFileDescriptor p : open) {
                try {
                    p.close();
                } catch (IOException ignored) {
                }
            }
            deleteTree(links);
        }
    }

    private interface Lines {
        void line(String l);
    }

    private static int run(List<String> args, int[] fds, Lines lines) throws IOException {
        int[] r = spawn(args.toArray(new String[0]), fds);
        if (r == null) throw new IOException("Can't start innoextract");
        // The stream owns the descriptor (a bare FileDescriptor from an unreferenced
        // ParcelFileDescriptor would be closed under the reader when that is collected).
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(r[1])), StandardCharsets.UTF_8))) {
            for (String l; (l = in.readLine()) != null; ) lines.line(l);
        }
        return waitFor(r[0]);
    }

    // What to tell about a failed run, from innoextract's last words.
    private static String failure(List<String> lines, int rc) {
        StringBuilder msg = new StringBuilder();
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("- ")) continue;
            if (t.toLowerCase().contains(".bin") && (t.contains("not found") || t.contains("could not open")
                    || t.contains("missing"))) return "missing:" + t;
            if (msg.length() > 0) msg.append('\n');
            msg.append(t);
        }
        if (msg.length() == 0) msg.append("innoextract ").append(rc);
        return msg.toString();
    }

    private static File find(File dir, String name) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.isDirectory() && k.getName().equalsIgnoreCase(name)) return k;
        for (File k : kids) {
            if (!k.isDirectory()) continue;
            File r = find(k, name);
            if (r != null) return r;
        }
        return null;
    }

    // Moves a folder's contents over another's (same storage: renames, no copying).
    private static void moveTree(File from, File to) throws IOException {
        if (!to.exists()) {
            File parent = to.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Can't create " + parent);
            if (from.renameTo(to)) return;
        }
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("Can't create " + to);
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File t = new File(to, k.getName());
            if (k.isDirectory()) {
                moveTree(k, t);
            } else {
                if (t.exists() && !t.delete()) throw new IOException("Can't replace " + t);
                if (!k.renameTo(t)) throw new IOException("Can't write " + t);
            }
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isLink(File f) {
        try {
            return android.system.Os.lstat(f.getPath()).st_mode != 0
                    && android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }
}
