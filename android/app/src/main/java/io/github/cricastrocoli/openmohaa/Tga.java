package io.github.cricastrocoli.openmohaa;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

// A small TGA reader (the game's menu pictures): true-colour 24/32-bit and greyscale 8-bit,
// uncompressed or run-length encoded.
final class Tga {
    private Tga() {}

    static Bitmap decode(InputStream in) throws IOException {
        byte[] d = readAll(in);
        if (d.length < 18) throw new IOException("TGA too short");
        int idLen = d[0] & 255, cmapType = d[1] & 255, type = d[2] & 255;
        int cmapLen = u16(d, 5), cmapEntry = d[7] & 255;
        int w = u16(d, 12), h = u16(d, 14), bpp = d[16] & 255, desc = d[17] & 255;
        boolean rle = type == 10 || type == 11;
        boolean grey = type == 3 || type == 11;
        if (!(type == 2 || type == 10 || grey) || w <= 0 || h <= 0 || w > 4096 || h > 4096)
            throw new IOException("Unsupported TGA");
        int bytes = bpp / 8;
        if (bytes != (grey ? 1 : 3) && bytes != 4) throw new IOException("Unsupported TGA depth");
        int pos = 18 + idLen + (cmapType != 0 ? cmapLen * ((cmapEntry + 7) / 8) : 0);
        int[] px = new int[w * h];
        int n = 0;
        while (n < px.length) {
            int count = 1;
            boolean packet = false;
            if (rle) {
                int hdr = d[pos++] & 255;
                count = (hdr & 127) + 1;
                packet = (hdr & 128) != 0;
            }
            if (packet) {
                int c = pixel(d, pos, bytes, grey);
                pos += bytes;
                for (int i = 0; i < count && n < px.length; i++) px[n++] = c;
            } else {
                if (!rle) count = px.length;
                for (int i = 0; i < count && n < px.length; i++, pos += bytes) px[n++] = pixel(d, pos, bytes, grey);
            }
        }
        // Rows are stored bottom-up unless the descriptor's top-left bit is set.
        if ((desc & 0x20) == 0) {
            int[] row = new int[w];
            for (int y = 0; y < h / 2; y++) {
                System.arraycopy(px, y * w, row, 0, w);
                System.arraycopy(px, (h - 1 - y) * w, px, y * w, w);
                System.arraycopy(row, 0, px, (h - 1 - y) * w, w);
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int pixel(byte[] d, int p, int bytes, boolean grey) {
        if (grey) {
            int g = d[p] & 255;
            return 0xff000000 | (g << 16) | (g << 8) | g;
        }
        int b = d[p] & 255, g = d[p + 1] & 255, r = d[p + 2] & 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;  // alpha ignored: these are opaque pictures
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 255) | ((d[p + 1] & 255) << 8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
