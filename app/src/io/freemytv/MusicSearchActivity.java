package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Music tab's search: a field and Free My TV's own keyboard, drawn here and moved with the remote (no system
 * keyboard, so no Amazon one). A hardware keyboard types too. The query goes back to the tab, which shows the music
 * engine's answer as the Search row.
 */
public class MusicSearchActivity extends Activity {
    interface Asked {
        void asked(String query);
    }

    /** Who gets the query (the home screen, in the same process). */
    static Asked asked;
    static final String EXTRA_QUERY = "query";
    private static final int MAX_LENGTH = 80;
    private static final String[] ROWS = {"abcdefghijklm", "nopqrstuvwxyz", "1234567890'-&"};

    private Click click;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        click = new Click(this);
        String start = getIntent().getStringExtra(EXTRA_QUERY);
        Keyboard k = new Keyboard(this, start == null ? "" : start);
        setContentView(k);
        k.requestFocus();
    }

    @Override protected void onDestroy() {
        click.release();
        super.onDestroy();
    }

    private void search(String query) {
        String q = query.trim();
        if (q.isEmpty()) return;
        click.open();
        if (asked != null) asked.asked(q);
        finish();
    }

    private final class Keyboard extends View {
        private static final int CHAR = 0, SPACE = 1, DELETE = 2, SEARCH = 3;

        private final class Key {
            final int kind, row;
            final char c;
            final String shown;
            final RectF rect = new RectF();

            Key(int kind, int row, char c) {
                this.kind = kind;
                this.row = row;
                this.c = c;
                shown = String.valueOf(c);
            }
        }

        private final StringBuilder query;
        private final List<Key> keys = new ArrayList<>();
        private int focused;
        private final float k;
        private final RectF field = new RectF(), cell = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text, hint, label, labelBold;
        private final Path icon = new Path();
        private boolean caret = true;
        private final Runnable blink = new Runnable() {
            @Override public void run() {
                caret = !caret;
                invalidate();
                postDelayed(this, 530);
            }
        };

        Keyboard(Context c, String start) {
            super(c);
            query = new StringBuilder(start);
            setFocusable(true);
            setFocusableInTouchMode(true);
            k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
            text = Ui.text(Ui.light, 52 * k, 0xFFEDEDEF);
            hint = Ui.text(Ui.light, 52 * k, 0x80EDEDEF);
            label = Ui.text(Ui.regular, 38 * k, 0xFFEDEDEF);
            label.setTextAlign(Paint.Align.CENTER);
            labelBold = Ui.text(Ui.semibold, 34 * k, 0xFFEDEDEF);
            labelBold.setTextAlign(Paint.Align.CENTER);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(4 * k);

            // 13 columns of 80 with gaps of 10: 1160 wide, as wide as the field above them.
            float unit = 80 * k, gap = 10 * k, height = 84 * k, rowGap = 14 * k;
            float left = (1920 * k - 1160 * k) / 2f;
            field.set(left, 150 * k, left + 1160 * k, 260 * k);
            float top = field.bottom + 60 * k;
            for (int r = 0; r < ROWS.length; r++) {
                for (int i = 0; i < ROWS[r].length(); i++) {
                    Key key = new Key(CHAR, r, ROWS[r].charAt(i));
                    key.rect.set(left + i * (unit + gap), top, left + i * (unit + gap) + unit, top + height);
                    keys.add(key);
                }
                top += height + rowGap;
            }
            int[][] bottom = {{SPACE, 6}, {DELETE, 3}, {SEARCH, 4}};
            float x = left;
            for (int[] b : bottom) {
                Key key = new Key(b[0], ROWS.length, ' ');
                float w = b[1] * unit + (b[1] - 1) * gap;
                key.rect.set(x, top, x + w, top + height);
                keys.add(key);
                x += w + gap;
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            postDelayed(blink, 530);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(blink);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(0xF20A0B0F);

            fill.setColor(0x24FFFFFF);
            canvas.drawRoundRect(field, 24 * k, 24 * k, fill);
            line.setColor(0x40FFFFFF);
            line.setStrokeWidth(2 * k);
            canvas.drawRoundRect(field, 24 * k, 24 * k, line);
            line.setStrokeWidth(4 * k);
            float pad = 36 * k, base = field.centerY() + Ui.halfCap(text);
            if (query.length() == 0) {
                canvas.drawText(Ui.tr("Search music"), field.left + pad, base, hint);
            }
            // A long query shows its end, where the typing is.
            float room = field.width() - 2 * pad;
            float w = text.measureText(query, 0, query.length());
            float x = field.left + pad - Math.max(0, w - room);
            canvas.save();
            canvas.clipRect(field.left + pad / 2, field.top, field.right - pad / 2, field.bottom);
            canvas.drawText(query, 0, query.length(), x, base, text);
            if (caret) {
                fill.setColor(0xFFEDEDEF);
                float cx = x + w + 4 * k, h = Ui.cap(text) * 0.75f;
                canvas.drawRect(cx, field.centerY() - h, cx + 3 * k, field.centerY() + h, fill);
            }
            canvas.restore();

            for (int i = 0; i < keys.size(); i++) {
                Key key = keys.get(i);
                boolean on = i == focused;
                int ink = on ? 0xFF0A0B0F : 0xFFEDEDEF;
                fill.setColor(on ? 0xFFFFFFFF : key.kind == SEARCH ? 0x33FFFFFF : 0x1AFFFFFF);
                cell.set(key.rect);
                if (on) cell.inset(-cell.height() * 0.03f, -cell.height() * 0.03f);   // focused: a little bigger, as on the home screen
                canvas.drawRoundRect(cell, 14 * k, 14 * k, fill);
                float cx = key.rect.centerX(), cy = key.rect.centerY();
                switch (key.kind) {
                    case CHAR:
                        label.setColor(ink);
                        canvas.drawText(key.shown, cx, cy + Ui.halfCap(label), label);
                        break;
                    case SEARCH:
                        labelBold.setColor(ink);
                        canvas.drawText(Ui.tr("Search"), cx, cy + Ui.halfCap(labelBold), labelBold);
                        break;
                    case SPACE:   // ⎵
                        icon.reset();
                        icon.moveTo(cx - 40 * k, cy - 4 * k);
                        icon.lineTo(cx - 40 * k, cy + 8 * k);
                        icon.lineTo(cx + 40 * k, cy + 8 * k);
                        icon.lineTo(cx + 40 * k, cy - 4 * k);
                        line.setColor(ink);
                        canvas.drawPath(icon, line);
                        break;
                    case DELETE:   // ⌫
                        icon.reset();
                        icon.moveTo(cx - 30 * k, cy);
                        icon.lineTo(cx - 14 * k, cy - 18 * k);
                        icon.lineTo(cx + 30 * k, cy - 18 * k);
                        icon.lineTo(cx + 30 * k, cy + 18 * k);
                        icon.lineTo(cx - 14 * k, cy + 18 * k);
                        icon.close();
                        icon.moveTo(cx - 2 * k, cy - 8 * k);
                        icon.lineTo(cx + 14 * k, cy + 8 * k);
                        icon.moveTo(cx + 14 * k, cy - 8 * k);
                        icon.lineTo(cx - 2 * k, cy + 8 * k);
                        line.setColor(ink);
                        canvas.drawPath(icon, line);
                        break;
                }
            }
        }

        @Override public boolean onKeyDown(int code, KeyEvent e) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return move(focused > 0 && keys.get(focused - 1).row == keys.get(focused).row ? focused - 1 : focused);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return move(focused + 1 < keys.size() && keys.get(focused + 1).row == keys.get(focused).row ? focused + 1 : focused);
                case KeyEvent.KEYCODE_DPAD_UP:
                    return move(nearest(keys.get(focused).row - 1));
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return move(nearest(keys.get(focused).row + 1));
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    press(keys.get(focused), code != KeyEvent.KEYCODE_DPAD_CENTER);
                    return true;
                case KeyEvent.KEYCODE_DEL:
                    delete();
                    return true;
                case KeyEvent.KEYCODE_SEARCH:
                    search(query.toString());
                    return true;
            }
            int u = e.getUnicodeChar();
            if (u > 0 && !Character.isISOControl(u)) {
                type((char) u);
                return true;
            }
            return super.onKeyDown(code, e);
        }

        /** OK on a key; Enter on a hardware keyboard searches whatever is focused. */
        private void press(Key key, boolean enter) {
            if (enter || key.kind == SEARCH) {
                search(query.toString());
                return;
            }
            if (key.kind == DELETE) delete();
            else if (key.kind == SPACE) type(' ');
            else type(key.c);
        }

        private void type(char c) {
            if (query.length() >= MAX_LENGTH || c == ' ' && (query.length() == 0 || query.charAt(query.length() - 1) == ' ')) return;
            query.append(c);
            typed();
        }

        /** Held OK on ⌫ repeats, so holding it clears the field. */
        private void delete() {
            if (query.length() == 0) return;
            query.setLength(query.length() - 1);
            typed();
        }

        private void typed() {
            click.move();
            caret = true;
            removeCallbacks(blink);
            postDelayed(blink, 530);
            invalidate();
        }

        /** The key of [row] closest under (or over) the focused one; the focused one when there's no such row. */
        private int nearest(int row) {
            float x = keys.get(focused).rect.centerX();
            int best = focused;
            float bestDistance = Float.MAX_VALUE;
            for (int i = 0; i < keys.size(); i++) {
                Key key = keys.get(i);
                if (key.row != row) continue;
                float d = Math.abs(key.rect.centerX() - x);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = i;
                }
            }
            return best;
        }

        private boolean move(int to) {
            if (to != focused) {
                focused = to;
                click.move();
                invalidate();
            }
            return true;
        }
    }
}
