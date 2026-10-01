package X;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.moblica.common.xmob.ui.WindowManager;

import app.fblite.extension.feedfont.OriginalRasterizer;

/**
 * Replacement for Facebook Lite's server glyph rasterizer X.0eF (the build strips the "$" from class
 * names in package X, since Java cannot start a class name with a digit).
 *
 * The server sends each glyph as a box size plus vector outlines. This keeps the box, so line layout
 * does not change, and fills it with the system font. Icons (private use characters) and characters
 * the system font lacks go to the app's own rasterizer.
 *
 * Only the constructor and A03 are called from outside. Names are for Facebook Lite 530.0.0.8.106.
 */
@SuppressWarnings("unused")
public final class $0eF {
    /**
     * The server font is Roboto with an em of about 0.755 x the glyph box height, measured from the
     * widths the server sends against Roboto's advances. Using the same em keeps the system font's
     * proportions; only glyphs wider than their box are squeezed.
     */
    private static final float EM_PER_BOX_HEIGHT = 0.755f;

    private final $0e1 atlasHolder;
    private final WindowManager windowManager;
    private final int boxHeight;
    private final int underlineY;
    private final boolean underline;
    private final Object[] args;
    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint();
    private final char[] one = new char[1];
    private OriginalRasterizer original;

    public $0eF($0e1 atlasHolder, WindowManager windowManager, int boxHeight, int unused1, int unused2,
                int underlineY, boolean bold, boolean unused3, boolean underline) {
        this.atlasHolder = atlasHolder;
        this.windowManager = windowManager;
        this.boxHeight = boxHeight;
        this.underlineY = underlineY;
        this.underline = underline;
        this.args = new Object[] {atlasHolder, windowManager, boxHeight, unused1, unused2, underlineY, bold, unused3, underline};

        // The atlas is an alpha mask, the app tints it when drawing.
        paint.setColor(0xFF000000);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
        linePaint.setColor(0xFF000000);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.min(boxHeight / 18.0f, 3.0f));
    }

    public final $1In A03(byte[] data, char c) {
        synchronized (lock) {
            if (data == null || data.length < 2) return null;

            // Header: type, width, [4 bytes if type 8], [big-endian short height if type 3 or 6].
            int type = data[0];
            int width = data[1];
            int pos = type == 8 ? 6 : 2;
            int height = boxHeight;
            if ((type == 3 || type == 6) && data.length >= pos + 2) {
                height = (short) ((data[pos] << 8) | (data[pos + 1] & 0xFF));
            }
            if (width <= 0 || height <= 0) return null;

            if (c != ' ' && !canDraw(c)) {
                // X.0eE does not cache null and asks again on the next draw, so a glyph the original
                // could not draw yet shows up once it can, instead of a cached tofu box.
                return rasterizeWithOriginal(data, c);
            }

            $1Ik atlas;
            $1Im slot;
            synchronized (atlasHolder.A02) {
                atlas = atlasHolder.A00;
                slot = atlas != null ? atlas.A01(width, height) : null;
                if (slot == null) {
                    // Same sizing as the original when the atlas is missing or full.
                    int side = Math.max(windowManager.A0C, windowManager.A0B);
                    int rows = (Math.min($0FD.A01(92, 8) * height, side) / height) * height;
                    Integer flags = $0e1.A04;
                    atlas = new $1Ik(side, rows, atlasHolder.A03, flags != null ? flags : 0);
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
            return new $1In(atlas, slot);
        }
    }

    private boolean canDraw(char c) {
        // Icons use the private use area, and the server also maps some letters there (for example ĩ).
        if (Character.isSurrogate(c) || (c >= 0xE000 && c <= 0xF8FF)) return false;
        one[0] = c;
        return paint.hasGlyph(new String(one));
    }

    private $1In rasterizeWithOriginal(byte[] data, char c) {
        if (original == null) {
            original = OriginalRasterizer.create(args);
            if (original == null) return null;
        }
        return ($1In) original.rasterize(data, c);
    }

    private void drawChar(Canvas canvas, char c, int left, int top, int width, int height) {
        paint.setTextScaleX(1f);
        paint.setTextSize(height * EM_PER_BOX_HEIGHT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        one[0] = c;
        float advance = paint.measureText(one, 0, 1);
        if (advance > width) {
            paint.setTextScaleX(width / advance);
            advance = width;
        }
        float x = left + (width - advance) / 2f;
        float baseline = top + (height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
        canvas.drawText(one, 0, 1, x, baseline, paint);
    }
}
