package io.idiotbox;

/** The sky's colors: the background gradient (Scene) and the screensaver take them from here. */
final class Shaders {
    private Shaders() {}

    /** Sky colors: three tones (background, middle, highlight), from 0 to 1. */
    static final class Palette {
        final float[] c = new float[9];

        Palette set(int a, int b, int d) {
            put(0, a); put(3, b); put(6, d);
            return this;
        }

        private void put(int i, int rgb) {
            c[i] = ((rgb >> 16) & 255) / 255f;
            c[i + 1] = ((rgb >> 8) & 255) / 255f;
            c[i + 2] = (rgb & 255) / 255f;
        }

        int color(int i) {
            return 0xFF000000 | (Math.round(c[i * 3] * 255) << 16) | (Math.round(c[i * 3 + 1] * 255) << 8) | Math.round(c[i * 3 + 2] * 255);
        }
    }

    // The sky: only the night palette in this build (no light mode, no following the sun).
    private static final int[] NIGHT = {0x070B1E, 0x16225A, 0x4B2F8F};

    /** Home, settings and screensaver: always the night palette. */
    static void dream(Palette out) {
        out.set(NIGHT[0], NIGHT[1], NIGHT[2]);
    }
}
