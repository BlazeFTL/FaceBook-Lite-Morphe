package X;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.moblica.common.xmob.ui.WindowManager;

/**
 * Replacement for Facebook Lite's server glyph rasterizer (X.0eF). Keeps the glyph boxes the
 * server sends, but fills them with the system font instead of the server's vector outlines.
 */
public final class ZZeF {
    private final ZZe1 atlasHolder;
    private final WindowManager windowManager;
    private final int charHeight;
    private final int underlineY;
    private final boolean underline;
    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint();
    private final char[] one = new char[1];
    private static int logged;

    public ZZeF(ZZe1 atlasHolder, WindowManager windowManager, int charHeight, int unused1, int unused2,
                int underlineY, boolean bold, boolean unused3, boolean underline) {
        this.atlasHolder = atlasHolder;
        this.windowManager = windowManager;
        this.charHeight = charHeight;
        this.underlineY = underlineY;
        this.underline = underline;
        paint.setColor(0xFF000000);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); android.util.Log.i("FbLiteSysFont", "rasterizer created h=" + charHeight);
        linePaint.setColor(0xFF000000);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.min(charHeight / 18.0f, 3.0f));
    }

    public final ZZIn A03(byte[] data, char c) {
        synchronized (lock) {
            if (data == null || data.length < 2) return null;
            int type = data[0];
            int width = data[1];
            int pos = 2;
            if (type == 8) pos += 4;
            int height = charHeight;
            if ((type == 3 || type == 6) && data.length >= pos + 2) {
                height = (short) ((data[pos] << 8) | (data[pos + 1] & 0xFF));
            }
            if (width <= 0 || height <= 0) return null;
            if (logged < 20) { logged++; android.util.Log.i("FbLiteSysFont", "glyph '" + c + "' type=" + type + " w=" + width + " h=" + height); }

            ZZIk atlas;
            ZZIm slot;
            synchronized (atlasHolder.A02) {
                atlas = atlasHolder.A00;
                slot = atlas != null ? atlas.A01(width, height) : null;
                if (atlas == null || slot == null) {
                    int side = Math.max(windowManager.A0C, windowManager.A0B);
                    int rows = (Math.min(ZZFD.A01(92, 8) * height, side) / height) * height;
                    Integer flags = ZZe1.A04;
                    atlas = new ZZIk(side, rows, atlasHolder.A03, flags != null ? flags : 0);
                    atlasHolder.A00 = atlas;
                    slot = atlas.A01(width, height);
                    if (slot == null) return null;
                }
            }

            Canvas canvas = atlas.A00().A04;
            int left = slot.A01, top = slot.A03, right = slot.A02, bottom = slot.A00;
            canvas.save();
            canvas.clipRect(left, top, right, bottom);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            if (c != ' ') drawChar(canvas, c, left, top, width, height);
            if (underline) canvas.drawLine(left, top + underlineY, right, top + underlineY, linePaint);
            canvas.restore();
            return new ZZIn(atlas, slot);
        }
    }

    private void drawChar(Canvas canvas, char c, int left, int top, int width, int height) {
        paint.setTextScaleX(1f);
        paint.setTextSize(height * 0.82f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        one[0] = c;
        float advance = paint.measureText(one, 0, 1);
        if (advance > width && advance > 0) {
            paint.setTextScaleX(width / advance);
            advance = width;
        }
        float x = left + (width - advance) / 2f;
        float baseline = top + (height - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        canvas.drawText(one, 0, 1, x, baseline, paint);
    }
}
