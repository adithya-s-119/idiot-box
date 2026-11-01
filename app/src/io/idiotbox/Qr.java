package io.idiotbox;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A QR code for a short text (the phone page's address): byte mode, error correction level L, versions 1 to 10 (up to
 * 271 bytes), mask 0. After Project Nayuki's QR Code generator (MIT), cut down to what Idiot Box needs.
 */
final class Qr {
    private Qr() {}

    // Level L, versions 1 to 10 (index 0 unused).
    private static final int[] ECC_PER_BLOCK = {0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18};
    private static final int[] BLOCKS = {0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4};

    /** The modules, [y][x], true for dark; null when the text is too long. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int ver = 1;
        while (ver <= 10 && dataCodewords(ver) * 8 < 4 + (ver < 10 ? 8 : 16) + data.length * 8) ver++;
        if (ver > 10) return null;

        // The data bits: mode, length, bytes, terminator, then padding to the capacity.
        int capacity = dataCodewords(ver) * 8;
        Bits bits = new Bits(capacity);
        bits.add(4, 4);
        bits.add(data.length, ver < 10 ? 8 : 16);
        for (byte b : data) bits.add(b & 0xFF, 8);
        bits.add(0, Math.min(4, capacity - bits.length));
        bits.add(0, (8 - bits.length % 8) % 8);
        for (int pad = 0xEC; bits.length < capacity; pad ^= 0xEC ^ 0x11) bits.add(pad, 8);

        Grid g = new Grid(ver);
        g.drawCodewords(interleave(bits.bytes, ver));
        g.applyMask();
        g.drawFormatBits();
        return g.modules;
    }

    private static int rawModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            result -= (25 * align - 10) * align - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    /** The data split into blocks, each with its Reed-Solomon codewords, then read across the blocks. */
    private static byte[] interleave(byte[] data, int ver) {
        int blocks = BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = divisor(eccLen);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
            k += dat.length;
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            byte[] ecc = remainder(dat, divisor);
            System.arraycopy(ecc, 0, block, block.length - eccLen, ecc.length);
            out[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, k = 0; i < out[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[k++] = out[j][i];
            }
        }
        return result;
    }

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    /** In GF(2^8) over x^8 + x^4 + x^3 + x^2 + 1. */
    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class Bits {
        final byte[] bytes;
        int length;

        Bits(int capacity) {
            bytes = new byte[capacity / 8];
        }

        void add(int value, int count) {
            for (int i = count - 1; i >= 0; i--, length++) {
                if (bit(value, i)) bytes[length >>> 3] |= (byte) (0x80 >>> (length & 7));
            }
        }
    }

    private static final class Grid {
        final int ver, size;
        final boolean[][] modules, function;

        Grid(int ver) {
            this.ver = ver;
            size = ver * 4 + 17;
            modules = new boolean[size][size];
            function = new boolean[size][size];
            for (int i = 0; i < size; i++) {
                set(6, i, i % 2 == 0);
                set(i, 6, i % 2 == 0);
            }
            finder(3, 3);
            finder(size - 4, 3);
            finder(3, size - 4);
            int[] align = alignment();
            for (int i = 0; i < align.length; i++) {
                for (int j = 0; j < align.length; j++) {
                    boolean corner = i == 0 && j == 0 || i == 0 && j == align.length - 1 || i == align.length - 1 && j == 0;
                    if (!corner) alignmentPattern(align[i], align[j]);
                }
            }
            drawFormatBits();   // reserves its modules; drawn for real after the mask
            if (ver >= 7) {
                int rem = ver;
                for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
                int bits = ver << 12 | rem;
                for (int i = 0; i < 18; i++) {
                    boolean b = bit(bits, i);
                    int a = size - 11 + i % 3, c = i / 3;
                    set(a, c, b);
                    set(c, a, b);
                }
            }
        }

        private void set(int x, int y, boolean dark) {
            modules[y][x] = dark;
            function[y][x] = true;
        }

        private void finder(int x, int y) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                    if (0 <= xx && xx < size && 0 <= yy && yy < size) set(xx, yy, dist != 2 && dist != 4);
                }
            }
        }

        private void alignmentPattern(int x, int y) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }

        private int[] alignment() {
            if (ver == 1) return new int[0];
            int n = ver / 7 + 2, step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
            int[] result = new int[n];
            result[0] = 6;
            for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
            return result;
        }

        /** Level L (format bits 01) with mask 0. */
        void drawFormatBits() {
            int data = 1 << 3, rem = data;
            for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            int bits = (data << 10 | rem) ^ 0x5412;
            for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
            set(8, 7, bit(bits, 6));
            set(8, 8, bit(bits, 7));
            set(7, 8, bit(bits, 8));
            for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
            for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
            for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
            set(8, size - 8, true);
        }

        /** The codewords in the zigzag of column pairs, right to left, skipping the function modules. */
        void drawCodewords(byte[] data) {
            int i = 0;
            for (int right = size - 1; right >= 1; right -= 2) {
                if (right == 6) right = 5;
                for (int vert = 0; vert < size; vert++) {
                    for (int j = 0; j < 2; j++) {
                        int x = right - j;
                        boolean upward = ((right + 1) & 2) == 0;
                        int y = upward ? size - 1 - vert : vert;
                        if (!function[y][x] && i < data.length * 8) {
                            modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                            i++;
                        }
                    }
                }
            }
        }

        /** Mask 0: (x + y) even. */
        void applyMask() {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
    }
}
