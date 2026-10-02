package io.github.emir.claudes40;

import javax.microedition.lcdui.Font;

/**
 * Colours and fonts for the custom screens. Two palettes (light "Gündüz",
 * dark "Gece") and three text sizes, chosen in Ayarlar. Warm paper
 * background and a blue accent, the colour of the AIKON mark (#0740DE;
 * lighter on the dark palette).
 */
final class Theme {

    // current palette (set by apply())
    static int bg;
    static int surface;
    static int border;
    static int ink;
    static int muted;
    static int accent;
    static int accentInk;
    static int bar;
    static int barInk;
    static int error;
    static int errorBg;
    static int testBar;
    static int selection;
    static int spark;

    static Font font = Font.getDefaultFont();
    static Font bold = font;
    static Font small = font;

    private Theme() {
    }

    /** True while the dark colours are in use. */
    static boolean dark;

    /** Settings theme 2: dark from 19:00 to 07:00 phone time. */
    static boolean wantsDark(Settings s) {
        if (s.theme != 2) {
            return s.theme == 1;
        }
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return hour >= 19 || hour < 7;
    }

    static void apply(Settings s) {
        dark = wantsDark(s);
        if (dark) {
            bg = 0x141417;
            surface = 0x25252B;
            border = 0x34343C;
            ink = 0xECE8E1;
            muted = 0x9A958C;
            accent = 0x6E95FF;
            accentInk = 0x0B1430;
            bar = 0x0B0B0D;
            barInk = 0xF4EFE6;
            error = 0xFF8A7A;
            errorBg = 0x3A2323;
            testBar = 0x8A4A00;
            selection = 0x1F2740;
            spark = 0x6E95FF;
        } else {
            bg = 0xFAF6EF;
            surface = 0xFFFFFF;
            border = 0xE4DCCD;
            ink = 0x26252C;
            muted = 0x7C766B;
            accent = 0x0740DE;
            accentInk = 0xFFFFFF;
            bar = 0x26252C;
            barInk = 0xFFFFFF;
            error = 0xB3261E;
            errorBg = 0xFBE4E1;
            testBar = 0xB35C00;
            selection = 0xE3EAFD;
            spark = 0x0740DE;
        }
        int size = s.fontSize == 0 ? Font.SIZE_SMALL : s.fontSize == 2 ? Font.SIZE_LARGE : Font.SIZE_MEDIUM;
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, size);
        bold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, size);
        small = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    /** Linear blend of two RGB colours, t in 0..256. */
    static int mix(int a, int b, int t) {
        int r = ((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t >> 8);
        int g = ((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t >> 8);
        int bl = (a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t >> 8);
        return (r << 16) | (g << 8) | bl;
    }
}
