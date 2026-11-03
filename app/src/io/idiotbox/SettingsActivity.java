package io.idiotbox;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Idiot Box's settings (the app's icon, "All Apps", or the gear on the home screen), as a sidebar like Google TV's:
 * a translucent window whose sidebar slides in from the right on black that fades out to the left, over the home
 * screen; its rows are pills, the focused one solid white as on the home screen. Every page opens in the same panel;
 * Back (or ←) on the first one slides it out. "Home screen" opens the Idiot Box / Original choice (with a check on the
 * current one); the switch runs in the main process (HomeMode) and this screen only asks for it and follows the state
 * file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
        overridePendingTransition(0, 0);   // the panel slides in by itself
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.idiotbox/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);   // the panel already slid out
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, PICKER = 3, SHELF_EDIT = 4, PHONE = 5;
        // The sidebar: PANEL_W wide, SIDE from the right edge, its text TOP from the top and bottom (and PAD in).
        private static final float PANEL_W = 680, SIDE = 48, TOP = 16, PAD = 36, FADE = 560;
        private static final float ROWS_TOP = 128, ROW_H = 70, ROW_GAP = 9, SECTION = 44, BREAK = 18;
        private static final int PICK_VISIBLE = 8;
        private static final int WHITE = 0xFFF4F4F6, INK = 0xFF0A0B0F;
        private static final String[] MAIN_SYMBOLS = {"home", "bedtime", "settings", "apps", "live_tv", "tv_settings"};
        private static final String[] SETTING_SYMBOLS = {"settings", "speaker", "sparkles", "sunny", "live_tv", "tv", "play", "sync", "wallpaper"};
        /** The mode and the picks' state, read from their files off the main thread. */
        private static final ExecutorService io = Executors.newSingleThreadExecutor();

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        // The panel coming in (1) and going out (0, then the activity closes).
        private final Spring slide = new Spring(0.42f, 0.95f, 0);
        private boolean closing;
        // The list picker (the language, or the place when the city typed matched several): what it shows, which one
        // is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor, also the tabs' (editingTabs): what it can hold (selected ones first, in order) and the
        // selection, by key (an app's package, a tab's number).
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTabs;
        private int pickFor, pickCurrent, pickScroll;
        private static final int PICK_LANGUAGE = -1, PICK_CITY = -2, PICK_MUSIC = -3, PICK_WALLPAPER = -4;
        // Home Settings → Music provider: the apps that share their music, as {package, name}, once found (null before).
        private List<String[]> providers;
        // Home Settings → Location: the places found for what was typed (when more than one), or why none was.
        private final List<String[]> cities = new ArrayList<>();
        private String locationNote = "";
        // Home Settings → Set up from your phone: the page's address and its QR code (null when the TV has no network).
        private String phoneUrl;
        private android.graphics.Bitmap phoneQr;
        private final Paint qrPaint = new Paint();
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private int polls;   // a poll answered after a pause and a resume is the old one's: dropped
        // Row text, made again only when the language (or, for the values, a setting) changes.
        private String[] mainLabels, labels, values;
        private int labelsGeneration = -1, valuesGeneration = -1, valuesVersion = -1;
        private String valuesNote;
        // Wrapped text, made again only when it changes: the hint under the rows, the TMDB credit, the phone page's lead.
        private Wrap hintWrap = new Wrap(), creditWrap = new Wrap(), leadWrap = new Wrap();
        private final String versionText;
        // The "idiotbox" wordmark, the first page's heading: a vector (res/drawable/wordmark.xml, from
        // tools/wordmark.html), 4000 × 1060 in 1000ths of its font size, the baseline 820 down.
        private final android.graphics.drawable.Drawable wordmark;
        private final Spoken spoken;
        private long lastNanos;
        private int w, h;
        private float k = 1;
        private Paint title, crumb, section, rowLabel, rowValue, link, white, shadow, fade, panelEdge, pillFill;
        private TextPaint hint, small, lead, rowFit;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                Context c = getContext();
                int asked = polls;
                io.execute(() -> {
                    JSONObject s = HomeMode.state(c), r = Recs.status(c);
                    boolean key = Recs.hasKey(c);
                    handler.post(() -> {
                        if (!running || asked != polls) return;
                        boolean changed = key != hasKey || !s.toString().equals(state.toString()) || !r.toString().equals(recs.toString());
                        state = s;
                        hasKey = key;
                        recs = r;
                        if (changed) invalidate();
                        handler.postDelayed(poll, 2000);
                    });
                });
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            slide.target = 1;
            versionText = versionName(a);
            wordmark = a.getDrawable(R.drawable.wordmark);
            setFocusable(true);
            setContentDescription("Idiot Box");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) return Ui.trf("Home Screen, {mode}", "mode", freeActive() ? "Idiot Box" : Ui.tr("Original"));
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("Tabs"), Ui.tr("TV Settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Idiot Box, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS: {
                    String[] labels = settingLabels(), values = settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTabs) return app[1] + ", " + (place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"));
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private float panelLeft() {
            return 1920 - SIDE - PANEL_W;
        }

        private RectF rowBox(int row) {
            float top = rowTop(row), left = panelLeft();
            focusBox.set((left + 16) * k, top * k, (left + PANEL_W - 16) * k, (top + ROW_H) * k);
            return focusBox;
        }

        /** The heading over `row` when it starts a section, "" when only a gap sets it apart, null inside a section. */
        private String sectionAt(int row) {
            if (screen == MAIN) return row == 5 ? "" : null;
            if (screen == HOME_SETTINGS) return row == 2 ? Ui.tr("Picks and weather") : row == 4 ? Ui.tr("Live TV") : row == 6 ? Ui.tr("Music") : row == 7 ? "" : null;
            return null;
        }

        /** Top of `row`, in the 1920 × 1080 space. */
        private float rowTop(int row) {
            float top = TOP + ROWS_TOP + row * (ROW_H + ROW_GAP);
            for (int i = 1; i <= row; i++) {
                String s = sectionAt(i);
                if (s != null) top += s.isEmpty() ? BREAK : SECTION;
            }
            return top;
        }

        void resume() {
            if (running) return;
            running = true;
            lastNanos = 0;
            polls++;
            handler.post(poll);
            invalidate();
            spoken.readAgain();
            if (screen == PHONE) openPhone();   // the page stopped while away
        }

        void pause() {
            running = false;
            polls++;
            PhoneSetup.stop();
            handler.removeCallbacks(poll);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 46 * k, 0xFFF4F4F6);
            crumb = Ui.text(Ui.semibold, 22 * k, 0x8CFFFFFF);
            section = Ui.text(Ui.semibold, 22 * k, 0x8CFFFFFF);
            rowLabel = Ui.text(Ui.medium, 28 * k, 0xF2FFFFFF);
            rowFit = new TextPaint(rowLabel);
            rowValue = Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF);
            hint = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF));
            small = new TextPaint(Ui.text(Ui.regular, 19 * k, 0x66FFFFFF));
            lead = new TextPaint(Ui.text(Ui.medium, 27 * k, 0xE6FFFFFF));
            link = Ui.text(Ui.medium, 24 * k, 0xF2FFFFFF);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(WHITE);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            // Black behind the sidebar, fading out to the left over FADE px (no box): the home screen shows through.
            float fadeEnd = (panelLeft() - 24) * k;
            fade = new Paint(Paint.DITHER_FLAG);
            fade.setShader(new android.graphics.LinearGradient(fadeEnd - FADE * k, 0, fadeEnd, 0,
                    new int[] {0x00000000, 0xA6000000, 0xF2000000}, new float[] {0, 0.55f, 1}, android.graphics.Shader.TileMode.CLAMP));
            panelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
            panelEdge.setStyle(Paint.Style.STROKE);
            panelEdge.setStrokeWidth(Math.max(1, k));
            panelEdge.setColor(0x24FFFFFF);
            pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            pillFill.setColor(0x1AFFFFFF);
            hintWrap = new Wrap();
            creditWrap = new Wrap();
            leadWrap = new Wrap();
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 6;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return 0;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            int rows = rowCount();
            boolean moving = false;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rows ? 1 : 0;
                pop[i].step(dt);
                moving |= !pop[i].settled();
            }
            slide.step(dt);
            moving |= !slide.settled();
            if (closing && slide.value < 0.02f) {   // slid out: the activity closes
                if (running) {
                    running = false;
                    post(activity::finish);
                }
            } else if (moving && running) {
                postInvalidateOnAnimation();
            }
            c.save();
            c.translate((1 - slide.value) * (PANEL_W + SIDE + 40) * k, 0);
            // The black comes in with the sidebar (and the fade with it), the home screen still there on the left.
            fade.setAlpha(Math.round(255 * Ui.clamp(slide.value)));
            c.drawRect((panelLeft() - 24 - FADE) * k, 0, w + (PANEL_W + SIDE + 40) * k, h, fade);
            drawHeader(c);
            if (screen == MAIN) drawMain(c);
            else if (screen == HOME_CHOICE) drawChoice(c);
            else if (screen == PICKER) drawPicker(c);
            else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == PHONE) drawPhone(c);
            else drawSettings(c);
            c.restore();
        }

        /** At the top of the panel: where you are (small) over the page's name. */
        private void drawHeader(Canvas c) {
            String name, from;
            if (screen == MAIN && wordmark != null) {
                drawWordmark(c);
                return;
            } else if (screen == MAIN) {
                name = Ui.tr("Settings");
                from = versionText;
            } else if (screen == PICKER) {
                name = pickFor == PICK_LANGUAGE ? Ui.tr("Language") : pickFor == PICK_MUSIC ? Ui.tr("Music provider")
                    : pickFor == PICK_WALLPAPER ? Ui.tr("Wallpaper") : Ui.tr("Location");
                from = Ui.tr("Home Settings");
            } else if (screen == PHONE) {
                name = Ui.tr("Set up from your phone");
                from = Ui.tr("Home Settings");
            } else {
                name = screen == HOME_CHOICE ? Ui.tr("Home Screen") : screen == HOME_SETTINGS ? Ui.tr("Home Settings") : editingTabs ? Ui.tr("Tabs") : Ui.tr("Shelf");
                from = Ui.tr("Settings");
            }
            float x = (panelLeft() + PAD) * k, room = (PANEL_W - 2 * PAD) * k;
            c.drawText(fit(from, crumb, room), x, (TOP + 40) * k, crumb);
            c.drawText(fit(name, title, room), x - Ui.leftBearing(title, name), (TOP + 100) * k, title);
        }

        /** The first page's heading: the wordmark where a page's name goes, the version beside it. */
        private void drawWordmark(Canvas c) {
            float x = (panelLeft() + PAD) * k, baseline = (TOP + 100) * k;
            // Its letters about the size of a page's name (46 px): drawn at 56 px of Poppins, 1000ths of that per unit.
            float u = 56 * k / 1000, top = baseline - 820 * u;
            wordmark.setBounds(Math.round(x), Math.round(top), Math.round(x + 4000 * u), Math.round(top + 1060 * u));
            wordmark.draw(c);
            c.drawText(versionText, x + 4000 * u + 14 * k, baseline, crumb);
        }

        /** The text area under the rows: from `top` to the panel's bottom padding. */
        private float textLeft() {
            return (panelLeft() + PAD) * k;
        }

        private float textRoom() {
            return (PANEL_W - 2 * PAD) * k;
        }

        /** The hint under the rows (what OK does), wrapped. */
        private void drawHint(Canvas c, float top, String text) {
            // As many lines as fit above the panel's bottom padding (Home Settings leaves room for one or two).
            int lines = (int) (((1080 - TOP) * k - top) / hint.getFontSpacing());
            if (text.isEmpty() || lines < 1) return;
            StaticLayout l = hintWrap.get(text, hint, Math.round(textRoom()), Math.min(6, lines));
            c.save();
            c.translate(textLeft(), top);
            l.draw(c);
            c.restore();
        }

        /** Below the last of `rows` rows, with room for the hint. */
        private float belowRows(int rows) {
            return (rows == 0 ? TOP + ROWS_TOP : rowTop(rows - 1) + ROW_H) * k + 36 * k;
        }

        /** `s`, cut with an ellipsis if it's wider than `room`. */
        private String fit(String s, Paint p, float room) {
            if (p.measureText(s) <= room) return s;
            rowFit.set(p);
            return TextUtils.ellipsize(s, rowFit, Math.max(0, room), TextUtils.TruncateAt.END).toString();
        }

        /**
         * Row `i` at rowBox(i), in rowRect: a pill, like the home screen's tabs and chips. At rest a faint glass pill with a
         * hairline; focused, solid white, a little bigger and lifted. Returns how focused it is.
         */
        private float drawRowBackground(Canvas c, int i) {
            rowRect.set(rowBox(i));
            float f = Ui.clamp(pop[i].value);
            if (f < 0.99f) {
                float r = rowRect.height() / 2;
                pillFill.setColor(Ui.alpha(0x14FFFFFF, 1 - f));
                c.drawRoundRect(rowRect, r, r, pillFill);
                panelEdge.setAlpha(Math.round(0x24 * (1 - f)));
                c.drawRoundRect(rowRect, r, r, panelEdge);
                panelEdge.setAlpha(0x24);
            }
            if (f > 0.01f) {
                rowRect.inset(-6 * k * f, -3 * k * f);
                float r = rowRect.height() / 2;
                tmp.set(rowRect);
                tmp.offset(0, 8 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(rowRect, r, r, white);
            }
            return f;
        }

        /**
         * A row's symbol (if any), name, value (under the name, as on Google TV) and trailing mark ("chevron_right" when
         * OK opens a page, "check" for the one in use); dark on the focused row, faded when `dim`.
         */
        private void drawRowText(Canvas c, float f, String symbol, String label, String value, String mark, boolean dim) {
            float left = rowRect.left + 22 * k, right = rowRect.right - 22 * k, cy = rowRect.centerY();
            int ink = Ui.blend(dim ? 0x80FFFFFF : 0xF2FFFFFF, dim ? 0x990A0B0F : INK, f);
            if (symbol != null) {
                Symbols.draw(getContext(), c, symbol, left + 16 * k, cy, 27 * k, ink);
                left += 56 * k;
            }
            if (mark != null) {
                boolean check = mark.equals("check");
                float size = (check ? 26 : 22) * k, mw = Symbols.width(getContext(), mark, size);
                Symbols.draw(getContext(), c, mark, right - mw / 2, cy, size, check ? ink : Ui.blend(0x66FFFFFF, 0x990A0B0F, f));
                right -= mw + 16 * k;
            }
            rowLabel.setColor(ink);
            if (value.isEmpty()) {
                c.drawText(fit(label, rowLabel, right - left), left, cy + Ui.halfCap(rowLabel), rowLabel);
                return;
            }
            c.drawText(fit(label, rowLabel, right - left), left, rowRect.top + rowRect.height() * 0.46f, rowLabel);
            rowValue.setColor(Ui.blend(dim ? 0x59FFFFFF : 0x8CFFFFFF, 0x990A0B0F, f));
            c.drawText(fit(value, rowValue, right - left), left, rowRect.top + rowRect.height() * 0.82f, rowValue);
        }

        /** The headings over this page's sections (a section after a plain gap has none). */
        private void drawSections(Canvas c, int rows) {
            for (int i = 1; i < rows; i++) {
                String s = sectionAt(i);
                if (s != null && !s.isEmpty()) c.drawText(s, textLeft(), (rowTop(i) - 16) * k, section);
            }
        }

        private void drawMain(Canvas c) {
            if (mainLabels == null || labelsGeneration != Ui.generation) settingLabels();
            String[] labels = mainLabels;
            for (int i = 0; i < labels.length; i++) {
                float f = drawRowBackground(c, i);
                drawRowText(c, f, MAIN_SYMBOLS[i], labels[i], i == 0 ? (freeActive() ? "Idiot Box" : Ui.tr("Original")) : "",
                        i == 1 ? null : "chevron_right", false);
            }

            // At the bottom: the credit TMDB asks for (its terms want it in the app; the picks come from it).
            drawCredit(c);
        }

        /** How the picks are doing, in one line. */
        private String recsLine() {
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) return Ui.tr("No TMDB key: add one in Home Settings.");
            if (!error.isEmpty()) return Ui.trf("Error: {error}.", "error", error);
            if (count > 0) return Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            return Ui.tr("Fetching titles…");
        }

        private void drawCredit(Canvas c) {
            String credit = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            StaticLayout l = creditWrap.get(credit, small, Math.round(textRoom()), 4);
            c.save();
            c.translate(textLeft(), (1080 - TOP - 12) * k - l.getHeight());
            l.draw(c);
            c.restore();
        }

        /** Home screen choice: Idiot Box or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            String[][] options = {{"Idiot Box", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            String[] symbols = {"sparkles", "tv"};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float f = drawRowBackground(c, i);
                drawRowText(c, f, symbols[i], options[i][0], options[i][1], i == current ? "check" : null, false);
            }
        }


        private static String versionName(Context c) {
            try {
                return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "";
            }
        }

        boolean onKey(KeyEvent e) {
            if (closing) return true;
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    boolean right = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT;
                    if (!down) return true;
                    if (screen == SHELF_EDIT) {   // the editors: ← → move the focused app or tab
                        moveShelfApp(right ? 1 : -1);
                        invalidate();
                    } else if (e.getRepeatCount() > 0) {
                        return true;   // held: one step, not one per repeat
                    } else if (!right) {
                        back();   // as in a sidebar: ← goes back, and from the first page closes it
                        invalidate();
                        spoken.focusMoved();
                    } else if (opensPage()) {
                        activate();   // → on a row that opens a page or a list: as OK
                    } else if (screen == HOME_SETTINGS && focus == 1) {
                        adjust();
                        invalidate();
                    }
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) back();
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        /** Does the focused row open a page or a list (the ones with a chevron)? */
        private boolean opensPage() {
            return (screen == MAIN || screen == HOME_SETTINGS) && focus != 1;
        }

        /** Back: one page up, and from the first one the panel slides out (then the activity closes, doFrame). */
        private void back() {
            if (screen == MAIN) {
                closing = true;
                slide.target = 0;
                return;
            }
            if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                focus = 3;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_WALLPAPER) {
                focus = 8;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_MUSIC) {
                focus = 6;
                screen = HOME_SETTINGS;
            } else if (screen == PHONE) {
                PhoneSetup.stop();
                focus = 7;
                screen = HOME_SETTINGS;
            } else {
                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTabs ? 4 : 3) : 0;
                screen = MAIN;
            }
            for (Spring s : pop) s.snap(0);
        }

        // MARK: - Home Settings

        /**
         * Home Settings: language, sounds, then what config.properties set at first: the TMDB key, the city, Live TV's
         * playlists and guides (the city typed here, the rest from the phone page).
         */
        private String[] settingLabels() {
            if (labels == null || labelsGeneration != Ui.generation) {
                labelsGeneration = Ui.generation;
                labels = new String[] {Ui.tr("Language"), Ui.tr("Navigation sounds"), Ui.tr("TMDB key"), Ui.tr("Location"),
                        Ui.tr("Live TV channels"), Ui.tr("TV guide"), Ui.tr("Music provider"), Ui.tr("Set up from your phone"),
                        Ui.tr("Wallpaper")};
                mainLabels = new String[] {Ui.tr("Home Screen"), Ui.tr("Screensaver"), Ui.tr("Home Settings"), Ui.tr("Shelf"),
                        Ui.tr("Tabs"), Ui.tr("TV Settings")};
            }
            return labels;
        }

        private String[] settingValues() {
            if (values != null && valuesGeneration == Ui.generation && valuesVersion == Prefs.version
                    && locationNote.equals(valuesNote)) {
                return values;
            }
            valuesGeneration = Ui.generation;
            valuesVersion = Prefs.version;
            valuesNote = locationNote;
            String[] v = values = new String[9];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            v[1] = Prefs.sounds() ? Ui.tr("On") : Ui.tr("Off");
            v[2] = Prefs.tmdbKey().isEmpty() ? Ui.tr("Not set") : Ui.tr("Saved");
            v[3] = !locationNote.isEmpty() ? locationNote
                    : Prefs.city().isEmpty() ? Prefs.latitude() + ", " + Prefs.longitude() : Prefs.city();
            v[4] = Prefs.ownPlaylists() ? Ui.plural("plural|one:{n} playlist|other:{n} playlists", Prefs.playlists().size()) : Ui.tr("Built-in");
            v[5] = Prefs.ownGuides() ? Ui.plural("plural|one:{n} guide|other:{n} guides", Prefs.guides().size()) : Ui.tr("Built-in");
            v[6] = MusicEngine.pkg().isEmpty() ? Ui.tr("None") : MusicEngine.name(getContext());
            v[7] = "";
            v[8] = wallpaperLabel(Prefs.wallpaperSource());
            return v;
        }

        private static String[] wallpaperSources() {
            return new String[] {"off", "bing", "nasa", "picsum"};
        }

        private static String wallpaperLabel(String source) {
            switch (source) {
                case "bing": return "Bing";
                case "nasa": return "NASA APOD";
                case "picsum": return "Picsum";
                default: return Ui.tr("Off");
            }
        }

        private void drawSettings(Canvas c) {
            String[] labels = settingLabels(), values = settingValues();
            drawSections(c, labels.length);
            for (int i = 0; i < labels.length; i++) {
                float f = drawRowBackground(c, i);
                drawRowText(c, f, SETTING_SYMBOLS[i], labels[i], values[i], i == 1 ? null : "chevron_right", false);
            }
            String what;
            switch (focus) {
                case 0: what = Ui.tr("OK opens the list."); break;
                case 1: what = Ui.tr("OK or → changes it."); break;
                case 2: what = Ui.tr("OK sets it from your phone.") + "\n" + recsLine(); break;
                case 3: what = Ui.tr("Your city, for the weather.") + "\n" + Ui.tr("OK opens the keyboard."); break;
                case 6: what = Ui.tr("The app the Music tab browses and searches."); break;
                case 8: what = Ui.tr("OK opens the list."); break;
                default: what = Ui.tr("OK sets it from your phone.");
            }
            drawHint(c, belowRows(labels.length), what);
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        /** OK or → on Navigation sounds turns them on or off (the other rows open something). */
        private void adjust() {
            if (focus != 1) return;
            Prefs.setSounds(!Prefs.sounds());
            HomeData.get(getContext()).settingsChanged();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Location (typed on the TV) and the phone page

        /** Idiot Box's keyboard for the city; the places found go to cityFound. */
        private void askCity() {
            MusicSearchActivity.asked = q -> new Thread(() -> {
                List<String[]> found;
                try {
                    found = HomeData.findCity(q);
                } catch (Exception e) {
                    AppLog.w("settings: no city (" + e.getMessage() + ")");
                    found = new ArrayList<>();
                }
                List<String[]> places = found;
                handler.post(() -> cityFound(q, places));
            }, "find-city").start();
            activity.startActivity(new Intent(activity, MusicSearchActivity.class)
                    .putExtra(MusicSearchActivity.EXTRA_QUERY, Prefs.city())
                    .putExtra(MusicSearchActivity.EXTRA_HINT, Ui.tr("Your city"))
                    .putExtra(MusicSearchActivity.EXTRA_GO, Ui.tr("Done")));
        }

        /** One place: it's the city. Several: a list to pick from. None: the row says so. */
        private void cityFound(String typed, List<String[]> found) {
            if (found.isEmpty()) {
                locationNote = Ui.trf("Couldn't find “{city}”.", "city", typed);
            } else if (found.size() == 1) {
                setCity(found.get(0));
            } else {
                cities.clear();
                cities.addAll(found);
                pickFor = PICK_CITY;
                pickLabels.clear();
                for (String[] place : found) pickLabels.add(place[0]);
                pickCurrent = -1;
                pickScroll = 0;
                focus = 0;
                screen = PICKER;
            }
            invalidate();
            spoken.focusMoved();
        }

        /** [place]: label, city, latitude, longitude (HomeData.findCity); the weather follows it. */
        private void setCity(String[] place) {
            Prefs.setLocation(place[1], place[2], place[3]);
            locationNote = "";
            HomeData.get(getContext()).settingsChanged();
        }

        /** The phone page, listening while this screen is open. */
        private void openPhone() {
            screen = PHONE;
            focus = 0;
            phoneUrl = PhoneSetup.start(() -> {
                HomeData.get(getContext()).settingsChanged();
                invalidate();
            });
            phoneQr = phoneUrl == null ? null : qrBitmap(Qr.encode(phoneUrl));
        }

        /** The code at one pixel a module, with the four-module white border readers need. */
        private static android.graphics.Bitmap qrBitmap(boolean[][] m) {
            if (m == null) return null;
            int border = 4, size = m.length + 2 * border;
            int[] pixels = new int[size * size];
            java.util.Arrays.fill(pixels, 0xFFFFFFFF);
            for (int y = 0; y < m.length; y++) {
                for (int x = 0; x < m.length; x++) if (m[y][x]) pixels[(y + border) * size + x + border] = 0xFF000000;
            }
            return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888);
        }

        /** What to do, the code on a white card, the address in a glass pill, how to stop it. */
        private void drawPhone(Canvas c) {
            float x = textLeft(), room = textRoom(), top = (TOP + ROWS_TOP) * k;
            if (phoneQr == null) {
                drawHint(c, top, Ui.tr("No network: connect the TV to Wi-Fi first."));
                return;
            }
            StaticLayout l = leadWrap.get(Ui.tr("On your phone, on the same Wi-Fi, scan the code or open:"), lead,
                    Math.round(room), 3);
            c.save();
            c.translate(x, top);
            l.draw(c);
            c.restore();
            // The code needs its white border to scan: a white card, lifted like a focused row.
            float size = 440 * k, cx = x + room / 2, r = 26 * k, cardTop = top + l.getHeight() + 30 * k;
            rowRect.set(cx - size / 2, cardTop, cx + size / 2, cardTop + size);
            tmp.set(rowRect);
            tmp.offset(0, 12 * k);
            shadow.setColor(0x73000000);
            c.drawRoundRect(tmp, r, r, shadow);
            white.setAlpha(255);
            c.drawRoundRect(rowRect, r, r, white);
            rowRect.inset(20 * k, 20 * k);
            c.drawBitmap(phoneQr, null, rowRect, qrPaint);   // no filtering: sharp modules
            String url = fit(phoneUrl, link, room - 48 * k);
            float pillTop = cardTop + size + 34 * k, pw = link.measureText(url) + 48 * k;
            tmp.set(cx - pw / 2, pillTop, cx + pw / 2, pillTop + 60 * k);
            pillFill.setColor(0x1AFFFFFF);   // the rows' pills change it
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, pillFill);
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, panelEdge);
            c.drawText(url, tmp.left + 24 * k, tmp.centerY() + Ui.halfCap(link), link);
            drawHint(c, tmp.bottom + 34 * k, Ui.tr("Back closes this page and turns the phone page off."));
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            editingTabs = false;
            shelfOrder.clear();
            // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the limit)
            // without a row to take it off.
            PackageManager pm = getContext().getPackageManager();
            for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Settings > Tabs: the shelf editor over the four tabs (shown ones first, in order). */
        private void openTabsEditor() {
            editingTabs = true;
            shelfOrder.clear();
            for (int tab : Prefs.tabs()) shelfOrder.add(String.valueOf(tab));
            List<String[]> all = new ArrayList<>();
            String[] names = {Ui.tr("Continue watching"), Ui.tr("Explore"), Ui.tr("Live TV"), Ui.tr("Music")};
            for (int i = 0; i < names.length; i++) all.add(new String[] {String.valueOf(i), names[i]});
            rebuildShelfRows(all);
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTabs) {
                List<Integer> tabs = new ArrayList<>();
                for (String key : shelfOrder) tabs.add(Integer.parseInt(key));
                Prefs.setTabs(tabs);
            } else {
                Prefs.setShelf(shelfOrder);
            }
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTabs || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            if (shelfRows.isEmpty()) return;
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float f = drawRowBackground(c, i);
                drawRowText(c, f, null, app[1], place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), null, place < 0);
            }
            drawHint(c, belowRows(rows), editingTabs ? Ui.tr("OK shows or hides a tab. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF));
        }

        private void openLanguagePicker() {
            pickFor = PICK_LANGUAGE;
            pickLabels.clear();
            String[] choices = languageChoices();
            for (String code : choices) pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
            pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /**
         * The music provider: None, then the apps that share their music, found by asking each app with a media browser
         * (a few seconds: until then, None alone).
         */
        private void openMusicPicker() {
            pickFor = PICK_MUSIC;
            providers = null;
            fillMusicPicker();
            screen = PICKER;
            focus = pickCurrent;
            MusicEngine.findProviders(getContext(), found -> {
                if (screen != PICKER || pickFor != PICK_MUSIC) return;
                providers = found;
                fillMusicPicker();
                focus = Math.min(pickCurrent, rowCount() - 1);   // on the one in use (until now only None was there)
                invalidate();
                spoken.focusMoved();
            });
        }

        private void fillMusicPicker() {
            pickLabels.clear();
            pickLabels.add(Ui.tr("None"));
            String current = Prefs.musicProvider();
            pickCurrent = 0;
            if (providers != null) {
                for (String[] p : providers) {
                    if (p[0].equals(current)) pickCurrent = pickLabels.size();
                    pickLabels.add(p[1]);
                }
            }
            pickScroll = 0;
        }

        /** The wallpaper source: Off, Bing, NASA APOD or Picsum. */
        private void openWallpaperPicker() {
            pickFor = PICK_WALLPAPER;
            pickLabels.clear();
            pickLabels.add(Ui.tr("Off"));
            pickLabels.add("Bing");
            pickLabels.add("NASA APOD");
            pickLabels.add("Picsum");
            pickCurrent = java.util.Arrays.asList(wallpaperSources()).indexOf(Prefs.wallpaperSource());
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the languages, the places, the music apps): eight rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float f = drawRowBackground(c, i);
                drawRowText(c, f, null, pickLabels.get(at), "", at == pickCurrent ? "check" : null, false);
            }
            String hint = (pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels.");
            if (pickFor == PICK_MUSIC) {
                hint = (providers == null ? Ui.tr("Looking for music apps…") : Ui.tr("Only apps that share their music are listed."))
                        + "\n" + hint;
            }
            drawHint(c, belowRows(rows), hint);
        }

        private void activate() {
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already) HomeMode.request(getContext(), wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_WALLPAPER) {
                Prefs.setWallpaperSource(wallpaperSources()[pickScroll + focus]);
                focus = 8;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_MUSIC) {
                int at = pickScroll + focus;
                String pkg = at == 0 || providers == null ? "" : providers.get(at - 1)[0];
                if (!pkg.equals(Prefs.musicProvider())) {
                    Prefs.setMusicProvider(pkg);
                    MusicEngine.providerChanged();
                }
                focus = 6;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                setCity(cities.get(pickScroll + focus));
                focus = 3;
                screen = HOME_SETTINGS;
            } else if (screen == PHONE) {
                return;
            } else if (screen == SHELF_EDIT) {
                if (!shelfRows.isEmpty()) toggleShelfApp();
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openLanguagePicker();
            } else if (screen == HOME_SETTINGS && focus == 1) {
                adjust();
                invalidate();
                return;
            } else if (screen == HOME_SETTINGS && focus == 3) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && focus == 6) {
                openMusicPicker();
            } else if (screen == HOME_SETTINGS && focus == 8) {
                openWallpaperPicker();
            } else if (screen == HOME_SETTINGS) {
                openPhone();
            } else if (focus == 0) {
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor();
            } else if (focus == 4) {
                openTabsEditor();
            } else {
                // The same settings page the home screen's shortcut opens (on Fire TV, Amazon's own).
                try {
                    activity.startActivity(HomeApps.tvSettings(activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLog.w("couldn't open TV Settings: " + e.getMessage());
                }
                return;
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }

        /** Text wrapped to a width, made again only when the text or the width changes. */
        private static final class Wrap {
            private StaticLayout layout;
            private String text = "";

            StaticLayout get(String s, TextPaint p, int width, int maxLines) {
                if (layout == null || !s.equals(text) || layout.getWidth() != width) {
                    text = s;
                    layout = StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, width))
                            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                return layout;
            }
        }
    }
}
