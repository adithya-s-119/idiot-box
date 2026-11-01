package io.idiotbox;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Idiot Box's settings (the app's icon, "All Apps", or the gear on the home screen), as a sidebar like Google TV's:
 * a translucent window whose glass panel slides in from the right over the dimmed home screen, the focused row solid
 * white as on the home screen. Every page opens in the same panel; Back on the first one slides it out. "Home screen"
 * opens the Idiot Box / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
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

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, PICKER = 3, SHELF_EDIT = 4, PHONE = 5;
        // The panel floats 48 px in from the right, top and bottom; the rows run almost edge to edge in it, the text
        // PAD in from its sides.
        private static final float PANEL_W = 680, PANEL_INSET = 48, PANEL_RADIUS = 36, PAD = 36;
        private static final float ROWS_TOP = 168, ROW_H = 76, ROW_GAP = 8, SECTION = 50, BREAK = 20, ROW_RADIUS = 20;
        private static final int PICK_VISIBLE = 8;
        private static final int AMBER = 0xFFFFC266, WHITE = 0xFFF4F4F6, INK = 0xFF0A0B0F;
        private static final String[] MAIN_SYMBOLS = {"home", "bedtime", "settings", "apps", "tv_settings"};
        private static final String[] SETTING_SYMBOLS = {"settings", "speaker", "sparkles", "sunny", "live_tv", "tv", "sync"};

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        // The panel coming in (1) and going out (0, then the activity closes).
        private final Spring slide = new Spring(0.42f, 0.95f, 0);
        private boolean closing;
        // The list picker (the language, or the place when the city typed matched several): what it shows, which one
        // is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: what it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;
        private static final int PICK_LANGUAGE = -1, PICK_CITY = -2;
        // Home Settings → Location: the places found for what was typed (when more than one), or why none was.
        private final List<String[]> cities = new ArrayList<>();
        private String locationNote = "";
        // Home Settings → Set up from your phone: the page's address and its QR code (null when the TV has no network).
        private String phoneUrl;
        private android.graphics.Bitmap phoneQr;
        private final Paint qrPaint = new Paint();
        private final RectF tmp = new RectF(), rowRect = new RectF(), panelRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        // Wrapped text, made again only when it changes: the last error, the hint at the bottom, the TMDB credit.
        private Wrap errorWrap = new Wrap(), hintWrap = new Wrap(), creditWrap = new Wrap(), leadWrap = new Wrap();
        private final String versionText;
        private final Spoken spoken;
        private long lastNanos;
        private int w, h;
        private float k = 1;
        private Paint title, crumb, section, rowLabel, rowValue, header, link, white, shadow, panelFill, panelEdge, pillFill, scrim;
        private TextPaint notice, hint, small, warnText, lead, rowFit;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                invalidate();
                handler.postDelayed(this, 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            slide.target = 1;
            versionText = versionName(a);
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
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("TV Settings")};
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
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private float panelLeft() {
            return 1920 - PANEL_INSET - PANEL_W;
        }

        private RectF rowBox(int row) {
            float top = rowTop(row), left = panelLeft();
            focusBox.set((left + 16) * k, top * k, (left + PANEL_W - 16) * k, (top + ROW_H) * k);
            return focusBox;
        }

        /** The heading over `row` when it starts a section, "" when only a gap sets it apart, null inside a section. */
        private String sectionAt(int row) {
            if (screen == MAIN) return row == 4 ? "" : null;
            if (screen == HOME_SETTINGS) return row == 2 ? Ui.tr("Picks and weather") : row == 4 ? Ui.tr("Live TV") : row == 6 ? "" : null;
            return null;
        }

        /** Top of `row`, in the 1920 × 1080 space. */
        private float rowTop(int row) {
            float top = PANEL_INSET + ROWS_TOP + row * (ROW_H + ROW_GAP);
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
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            if (screen == PHONE) openPhone();   // the page stopped while away
        }

        void pause() {
            running = false;
            PhoneSetup.stop();
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (closing && slide.value < 0.02f) {
                running = false;
                activity.finish();
                return;
            }
            boolean moving = !slide.settled();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
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
            header = Ui.text(Ui.semibold, 20 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            warnText = new TextPaint(Ui.text(Ui.regular, 22 * k, AMBER));
            notice = new TextPaint(Ui.text(Ui.regular, 22 * k, 0xB3FFFFFF));
            hint = new TextPaint(Ui.text(Ui.regular, 22 * k, 0x8CFFFFFF));
            small = new TextPaint(Ui.text(Ui.regular, 19 * k, 0x66FFFFFF));
            lead = new TextPaint(Ui.text(Ui.medium, 27 * k, 0xE6FFFFFF));
            link = Ui.text(Ui.medium, 24 * k, 0xF2FFFFFF);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(WHITE);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            scrim = new Paint();
            // The home screen's smoked glass, near opaque (nothing blurred behind it here), lit a little at the top.
            panelFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            panelFill.setShader(new LinearGradient(0, PANEL_INSET * k, 0, (1080 - PANEL_INSET) * k,
                    0xF5232328, 0xF50E0E11, Shader.TileMode.CLAMP));
            panelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
            panelEdge.setStyle(Paint.Style.STROKE);
            panelEdge.setStrokeWidth(Math.max(1, k));
            panelEdge.setColor(0x24FFFFFF);
            pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            pillFill.setColor(0x1AFFFFFF);
            errorWrap = new Wrap();
            hintWrap = new Wrap();
            creditWrap = new Wrap();
            leadWrap = new Wrap();
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 5;
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
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            slide.step(dt);
            float in = Ui.clamp(slide.value);
            // The home screen stays behind, dimmed.
            scrim.setColor(Ui.alpha(0xFF000000, 0.55f * in));
            c.drawRect(0, 0, w, h, scrim);
            c.save();
            c.translate((1 - slide.value) * (PANEL_W + PANEL_INSET + 40) * k, 0);
            float left = panelLeft() * k;
            panelRect.set(left, PANEL_INSET * k, left + PANEL_W * k, (1080 - PANEL_INSET) * k);
            float r = PANEL_RADIUS * k;
            c.drawRoundRect(panelRect, r, r, panelFill);
            c.drawRoundRect(panelRect, r, r, panelEdge);
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
            if (screen == MAIN) {
                name = Ui.tr("Settings");
                from = versionText;
            } else if (screen == PICKER) {
                name = pickFor == PICK_LANGUAGE ? Ui.tr("Language") : Ui.tr("Location");
                from = Ui.tr("Home Settings");
            } else if (screen == PHONE) {
                name = Ui.tr("Set up from your phone");
                from = Ui.tr("Home Settings");
            } else {
                name = screen == HOME_CHOICE ? Ui.tr("Home Screen") : screen == HOME_SETTINGS ? Ui.tr("Home Settings") : Ui.tr("Shelf");
                from = Ui.tr("Settings");
            }
            float x = (panelLeft() + PAD) * k, room = (PANEL_W - 2 * PAD) * k;
            c.drawText(fit(from, crumb, room), x, (PANEL_INSET + 64) * k, crumb);
            c.drawText(fit(name, title, room), x - Ui.leftBearing(title, name), (PANEL_INSET + 124) * k, title);
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
            if (text.isEmpty()) return;
            StaticLayout l = hintWrap.get(text, hint, Math.round(textRoom()), 6, Layout.Alignment.ALIGN_NORMAL);
            c.save();
            c.translate(textLeft(), top);
            l.draw(c);
            c.restore();
        }

        /** Below the last of `rows` rows, with room for the hint. */
        private float belowRows(int rows) {
            return (rows == 0 ? PANEL_INSET + ROWS_TOP : rowTop(rows - 1) + ROW_H) * k + 36 * k;
        }

        /** `s`, cut with an ellipsis if it's wider than `room`. */
        private String fit(String s, Paint p, float room) {
            if (p.measureText(s) <= room) return s;
            rowFit.set(p);
            return TextUtils.ellipsize(s, rowFit, Math.max(0, room), TextUtils.TruncateAt.END).toString();
        }

        /** Row `i` at rowBox(i), in rowRect: nothing behind it at rest; focused, solid white, a little bigger and lifted. */
        private float drawRowBackground(Canvas c, int i) {
            rowRect.set(rowBox(i));
            float f = Ui.clamp(pop[i].value), r = ROW_RADIUS * k;
            if (f > 0.01f) {
                rowRect.inset(-6 * k * f, -3 * k * f);
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
            int ink = blend(dim ? 0x80FFFFFF : 0xF2FFFFFF, dim ? 0x990A0B0F : INK, f);
            if (symbol != null) {
                Symbols.draw(getContext(), c, symbol, left + 16 * k, cy, 27 * k, ink);
                left += 56 * k;
            }
            if (mark != null) {
                boolean check = mark.equals("check");
                float size = (check ? 26 : 22) * k, mw = Symbols.width(getContext(), mark, size);
                Symbols.draw(getContext(), c, mark, right - mw / 2, cy, size, check ? ink : blend(0x66FFFFFF, 0x990A0B0F, f));
                right -= mw + 16 * k;
            }
            rowLabel.setColor(ink);
            if (value.isEmpty()) {
                c.drawText(fit(label, rowLabel, right - left), left, cy + Ui.halfCap(rowLabel), rowLabel);
                return;
            }
            c.drawText(fit(label, rowLabel, right - left), left, rowRect.top + 35 * k, rowLabel);
            rowValue.setColor(blend(dim ? 0x59FFFFFF : 0x8CFFFFFF, 0x990A0B0F, f));
            c.drawText(fit(value, rowValue, right - left), left, rowRect.top + 62 * k, rowValue);
        }

        /** The headings over this page's sections (a section after a plain gap has none). */
        private void drawSections(Canvas c, int rows) {
            for (int i = 1; i < rows; i++) {
                String s = sectionAt(i);
                if (s != null && !s.isEmpty()) c.drawText(s, textLeft(), (rowTop(i) - 16) * k, section);
            }
        }

        private void drawMain(Canvas c) {
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"), Ui.tr("Home Settings"), Ui.tr("Shelf"), Ui.tr("TV Settings")};
            for (int i = 0; i < labels.length; i++) {
                float f = drawRowBackground(c, i);
                drawRowText(c, f, MAIN_SYMBOLS[i], labels[i], i == 0 ? (freeActive() ? "Idiot Box" : Ui.tr("Original")) : "",
                        i == 1 ? null : "chevron_right", false);
            }

            // Under the rows: what went wrong last (if anything), the picks and the notices.
            float x = textLeft(), room = textRoom(), bottom = (1080 - PANEL_INSET - PAD) * k, y = belowRows(labels.length) + 28 * k;
            String error = state.optString("error");
            if (!error.isEmpty()) {
                StaticLayout l = errorWrap.get(error, warnText, Math.round(room), 3, Layout.Alignment.ALIGN_NORMAL);
                c.save();
                c.translate(x, y - l.getLineBaseline(0));
                l.draw(c);
                c.restore();
                y += l.getLineBaseline(l.getLineCount() - 1) - l.getLineBaseline(0) + 56 * k;
            }
            y = drawRecs(c, x, room, y);
            if (y + 80 * k < bottom) drawNotices(c, x, room, y + 44 * k, bottom);
        }

        /** How the picks are doing, in one line. */
        private String recsLine() {
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) return Ui.tr("No TMDB key: add one in Home Settings.");
            if (error.equals(Recs.NO_KNOWN_APPS)) return Ui.tr("None of the TV's streaming apps can give picks.");
            if (error.equals(Recs.NO_APPS)) return Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            if (!error.isEmpty()) return Ui.trf("Error: {error}.", "error", error);
            if (count > 0) return Ui.trf("On · {n} titles from the TV's apps, 3 new ones every hour.", "n", count);
            return Ui.tr("Fetching titles…");
        }

        /** Home screen picks: status, and the credit TMDB asks for. Returns the bottom. */
        private float drawRecs(Canvas c, float x, float room, float y) {
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            c.drawText(fit(recsLine(), notice, room), x, y + 38 * k, notice);
            String credit = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) credit += "\n" + Ui.tr("Links to the titles by Watchmode.");
            StaticLayout l = creditWrap.get(credit, small, Math.round(room), 4, Layout.Alignment.ALIGN_NORMAL);
            c.save();
            c.translate(x, y + 54 * k);
            l.draw(c);
            c.restore();
            return y + 54 * k + l.getHeight();
        }

        private void drawNotices(Canvas c, float x, float room, float y, float bottom) {
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                float base = y + (38 + 34 * shown) * k;
                if (base > bottom) break;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(fit(line, notice, room), x, base, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 38 * k, notice);
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

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Idiot Box " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Idiot Box";
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
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == HOME_SETTINGS) {
                        adjust();
                        invalidate();
                    }
                    return true;
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
            } else if (screen == PHONE) {
                PhoneSetup.stop();
                focus = 6;
                screen = HOME_SETTINGS;
            } else {
                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? 3 : 0;
                screen = MAIN;
            }
            for (Spring s : pop) s.snap(0);
        }

        // MARK: - Home Settings

        /**
         * Home Settings: language, sounds, then what config.properties set at first: the TMDB key, the city, Live TV's
         * playlists and guides. The city is typed here; the rest come from the phone page.
         */
        private String[] settingLabels() {
            return new String[] {Ui.tr("Language"), Ui.tr("Navigation sounds"), Ui.tr("TMDB key"), Ui.tr("Location"),
                    Ui.tr("Live TV channels"), Ui.tr("TV guide"), Ui.tr("Set up from your phone")};
        }

        private String[] settingValues() {
            String[] v = new String[7];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            v[1] = Prefs.sounds() ? Ui.tr("On") : Ui.tr("Off");
            v[2] = Prefs.tmdbKey().isEmpty() ? Ui.tr("Not set") : Ui.tr("Saved");
            v[3] = !locationNote.isEmpty() ? locationNote
                    : Prefs.city().isEmpty() ? Prefs.latitude() + ", " + Prefs.longitude() : Prefs.city();
            v[4] = Prefs.ownPlaylists() ? Ui.plural("plural|one:{n} playlist|other:{n} playlists", Prefs.playlists().size()) : Ui.tr("Built-in");
            v[5] = Prefs.ownGuides() ? Ui.plural("plural|one:{n} guide|other:{n} guides", Prefs.guides().size()) : Ui.tr("Built-in");
            v[6] = "";
            return v;
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
                case 1: what = Ui.tr("OK or the arrows change the value."); break;
                case 2: what = Ui.tr("OK sets it from your phone.") + "\n" + recsLine(); break;
                case 3: what = Ui.tr("Your city, for the weather.") + "\n" + Ui.tr("OK opens the keyboard."); break;
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

        /** ← → on Navigation sounds turn them on or off (the other rows open something with OK). */
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
            float x = textLeft(), room = textRoom(), top = (PANEL_INSET + ROWS_TOP) * k;
            if (phoneQr == null) {
                drawHint(c, top, Ui.tr("No network: connect the TV to Wi-Fi first."));
                return;
            }
            StaticLayout l = leadWrap.get(Ui.tr("On your phone, on the same Wi-Fi, scan the code or open:"), lead,
                    Math.round(room), 3, Layout.Alignment.ALIGN_NORMAL);
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
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, pillFill);
            c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, panelEdge);
            c.drawText(url, tmp.left + 24 * k, tmp.centerY() + Ui.halfCap(link), link);
            drawHint(c, tmp.bottom + 34 * k, Ui.tr("Back closes this page and turns the phone page off."));
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
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
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
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
            drawHint(c, belowRows(rows), Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF));
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

        /** A long list (the languages, the places): eight rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float f = drawRowBackground(c, i);
                drawRowText(c, f, null, pickLabels.get(at), "", at == pickCurrent ? "check" : null, false);
            }
            drawHint(c, belowRows(rows), (pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."));
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

            StaticLayout get(String s, TextPaint p, int width, int maxLines, Layout.Alignment align) {
                if (layout == null || !s.equals(text) || layout.getWidth() != width) {
                    text = s;
                    layout = StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, width)).setAlignment(align)
                            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                return layout;
            }
        }
    }
}
