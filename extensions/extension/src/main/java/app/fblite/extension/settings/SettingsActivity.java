package app.fblite.extension.settings;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

/**
 * Settings screen of the patches, opened from the app icon's long-press menu. Built in code, since
 * the extension cannot add layout resources. Settings are grouped into cards, each row has an icon.
 */
public final class SettingsActivity extends Activity {
    private boolean vi;
    private boolean night;

    private int bg;
    private int surface;
    private int stroke;
    private int divider;
    private int textPrimary;
    private int textSecondary;
    private int accent;
    private int accentTint;
    private int onAccent;
    private int trackOff;
    private int thumbOff;
    private int thumbOn;
    private int ripple;

    private TextView value;
    private TextView preview;
    private int percent;
    private View qualityRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Material rather than DeviceDefault: some OEM DeviceDefault themes leave TextView text black in dark mode.
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(night ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        MorpheLog.init(this);
        vi = "vi".equals(Locale.getDefault().getLanguage());
        setTitle(t("Morphe settings", "Cài đặt Morphe"));
        initColors();

        percent = MorpheSettings.fontScalePercent(this);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.addView(header());

        root.addView(sectionLabel(t("Appearance", "Giao diện")));
        root.addView(fontCard());

        LinearLayout downloads = card();
        addToggle(downloads, MorpheSettings.VIDEO_DOWNLOAD, SettingsIcons.DOWNLOAD,
                t("Download videos", "Tải video"),
                t("Shows a download button on playing videos and reels. Saved to Movies/Facebook Lite.",
                        "Hiện nút tải khi đang phát video hoặc reel. Video lưu vào Movies/Facebook Lite."));
        qualityRow = addToggle(downloads, MorpheSettings.ASK_DOWNLOAD_QUALITY, SettingsIcons.HD,
                t("Choose quality", "Chọn chất lượng"),
                t("Asks for the quality (HD, SD...) on each download. Off: always saves the highest quality.",
                        "Hỏi chất lượng (HD, SD...) mỗi lần tải. Tắt thì luôn lưu chất lượng cao nhất."));
        if (qualityRow != null && !MorpheSettings.isEnabled(this, MorpheSettings.VIDEO_DOWNLOAD)
                && PatchedFeatures.allowed(MorpheSettings.VIDEO_DOWNLOAD)) {
            qualityRow.setAlpha(0.5f);
        }
        addSection(root, t("Downloads", "Tải xuống"), downloads);

        LinearLayout playback = card();
        addToggle(playback, MorpheSettings.AUTO_NEXT_REEL, SettingsIcons.SKIP_NEXT,
                t("Auto next reel", "Tự chuyển reel tiếp theo"),
                t("Moves on to the next reel when one ends, instead of playing it again.",
                        "Khi reel phát hết, chuyển sang reel tiếp theo thay vì phát lại."));
        addToggle(playback, MorpheSettings.SHORT_VIDEO_CONTROLS, SettingsIcons.FAST_FORWARD,
                t("Seek bar for short videos", "Thanh tua cho video ngắn"),
                t("Shows a seek bar and back/forward 10 second buttons on reels and videos under a minute, when the app's own controls are hidden.",
                        "Hiện thanh tua và nút tua lùi/tiến 10 giây trên reel và video dưới 1 phút khi điều khiển của app đang ẩn."));
        addToggle(playback, MorpheSettings.DOUBLE_TAP_SEEK, SettingsIcons.TOUCH_APP,
                t("Double tap to seek", "Chạm đúp để tua"),
                t("Double tap the left or right side to go back or forward 10 seconds. The middle is left to the app.",
                        "Chạm đúp bên trái hoặc phải để lùi hoặc tiến 10 giây. Phần giữa do app xử lý."));
        addToggle(playback, MorpheSettings.HIDE_OVER_COMMENTS, SettingsIcons.VISIBILITY_OFF,
                t("Hide buttons over comments", "Ẩn nút khi mở bình luận"),
                t("Hides the download button and seek bar while comments or a menu cover the video. Turn off if they vanish by mistake.",
                        "Ẩn nút tải và thanh tua khi bình luận hoặc menu phủ lên video. Tắt nếu nút biến mất nhầm."));
        addSection(root, t("Video player", "Trình phát video"), playback);

        LinearLayout ads = card();
        addToggle(ads, MorpheSettings.REELS_HIDE_ADS, SettingsIcons.MOVIE,
                t("Remove sponsored reels", "Xóa reel quảng cáo"),
                t("Collapses sponsored reels as they load, so a swipe lands on the next real reel. Applies to reels loaded after the Reels tab is opened.",
                        "Thu gọn reel quảng cáo ngay khi tải, để vuốt là tới reel thật kế tiếp. Áp dụng cho reel tải sau khi mở tab Reels."));
        addToggle(ads, MorpheSettings.STORIES_HIDE_ADS, SettingsIcons.BLOCK,
                t("Skip sponsored stories", "Bỏ qua story quảng cáo"),
                t("Moves past sponsored stories automatically. No download button shows on them.",
                        "Tự chuyển qua story được tài trợ. Không hiện nút tải trên các story này."));
        addSection(root, t("Ads", "Quảng cáo"), ads);

        LinearLayout profiles = card();
        addToggle(profiles, MorpheSettings.PROFILE_WHEN_NO_REELS, SettingsIcons.PERSON,
                t("Open profile when no reels", "Mở hồ sơ khi chưa có reel"),
                t("When a creator's Reels page says \"No reels yet\", jumps to their main profile (like 3 dot menu > View main profile).",
                        "Khi trang Reels của người đăng ghi \"Chưa có reel\", mở hồ sơ chính của họ (như menu 3 chấm > Xem hồ sơ chính)."));
        addToggle(profiles, MorpheSettings.PROFILE_ALWAYS, SettingsIcons.ACCOUNT_CIRCLE,
                t("Skip creator's Reels page", "Bỏ qua trang Reels của người đăng"),
                t("Tapping a creator's name in a reel opens their main profile, even when their Reels page has reels.",
                        "Bấm tên người đăng trong reel sẽ mở hồ sơ chính, kể cả khi trang Reels của họ có reel."));
        addSection(root, t("Creator profiles", "Hồ sơ người đăng"), profiles);

        LinearLayout debug = card();
        addToggle(debug, MorpheSettings.DEBUG_LOG, SettingsIcons.BUG_REPORT,
                t("Debug logging", "Ghi nhật ký gỡ lỗi"),
                t("Logs how Facebook loads ads (feed, Reels, Marketplace) and where videos come from. The log holds on-screen text, so keep it off unless needed.",
                        "Ghi cách Facebook tải quảng cáo (bảng tin, Reels, Marketplace) và nguồn video. Nhật ký chứa chữ hiện trên màn hình, nên chỉ bật khi cần."));
        debug.addView(divider());
        debug.addView(logButtons());
        root.addView(sectionLabel(t("Troubleshooting", "Khắc phục sự cố")));
        root.addView(debug);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root);
        final int side = dp(16);
        // The app targets a recent SDK, so the window is edge to edge: keep the content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(side + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    side + insets.getSystemWindowInsetRight(), dp(32) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(scroll);
        applySystemBars();
        update();
    }

    private String t(String en, String vietnamese) {
        return vi ? vietnamese : en;
    }

    private void initColors() {
        if (night) {
            bg = 0xFF101318;
            surface = 0xFF1A1F27;
            stroke = 0xFF2B323D;
            divider = 0xFF262C36;
            textPrimary = 0xFFE9ECF2;
            textSecondary = 0xFF9BA4B3;
            accent = 0xFF8AB4FF;
            accentTint = 0xFF24324F;
            onAccent = 0xFF0A1B3D;
            trackOff = 0xFF1A1F27;
            thumbOff = 0xFF8791A1;
            thumbOn = 0xFF0A1B3D;
        } else {
            bg = 0xFFF3F5F9;
            surface = 0xFFFFFFFF;
            stroke = 0xFFE1E5ED;
            divider = 0xFFEDF0F5;
            textPrimary = 0xFF1A1E26;
            textSecondary = 0xFF5E6676;
            accent = 0xFF1A6CF0;
            accentTint = 0xFFE7EFFE;
            onAccent = 0xFFFFFFFF;
            trackOff = 0xFFF0F2F7;
            thumbOff = 0xFF7C8596;
            thumbOn = 0xFFFFFFFF;
        }
        ripple = (accent & 0x00FFFFFF) | 0x26000000;
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    // ---- Sections and cards ----

    private LinearLayout header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        FrameLayout back = new FrameLayout(this);
        back.setBackground(ripple(0, dp(22)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription(t("Back", "Quay lại"));
        back.setOnClickListener(v -> finish());
        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(SettingsIcons.get(SettingsIcons.ARROW_BACK, textPrimary));
        back.addView(arrow, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        backParams.setMargins(-dp(8), 0, 0, 0);
        header.addView(back, backParams);

        TextView title = text(getTitle(), 30, textPrimary, true);
        title.setPadding(0, dp(12), 0, 0);
        header.addView(title);

        TextView subtitle = text(t("Changes apply right away, except the font size.",
                "Thay đổi có hiệu lực ngay, trừ cỡ chữ."), 14, textSecondary, false);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        header.addView(subtitle);
        return header;
    }

    private TextView sectionLabel(String label) {
        TextView view = text(label, 13, accent, true);
        view.setLetterSpacing(0.04f);
        view.setPadding(dp(8), dp(24), 0, dp(8));
        return view;
    }

    private void addSection(LinearLayout root, String label, LinearLayout card) {
        // A section whose patches were not selected has no rows, so it is left out entirely.
        if (card.getChildCount() == 0) return;
        root.addView(sectionLabel(label));
        root.addView(card);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(dp(20));
        shape.setStroke(dp(1), stroke);
        card.setBackground(shape);
        card.setClipToOutline(true);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return card;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.setMargins(dp(70), 0, dp(16), 0);
        line.setLayoutParams(params);
        return line;
    }

    // ---- Font size ----

    private LinearLayout fontCard() {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(16), dp(16), dp(4));
        top.addView(iconTile(SettingsIcons.FORMAT_SIZE));
        top.addView(textColumn(t("Font size", "Cỡ chữ"),
                t("Scales Facebook Lite's text relative to the system font size. The app lays itself out for it on restart.",
                        "Chỉnh cỡ chữ Facebook Lite so với cỡ chữ hệ thống. Cần mở lại app để Facebook dựng lại bố cục.")));
        value = text("", 13, accent, true);
        value.setGravity(Gravity.CENTER);
        value.setPadding(dp(12), dp(5), dp(12), dp(5));
        value.setBackground(rounded(accentTint, dp(14)));
        top.addView(value);
        card.addView(top);

        LinearLayout sliderBox = new LinearLayout(this);
        sliderBox.setOrientation(LinearLayout.VERTICAL);
        sliderBox.setPadding(dp(16), dp(4), dp(16), 0);

        final SeekBar slider = new SeekBar(this);
        slider.setMax((MorpheSettings.MAX_PERCENT - MorpheSettings.MIN_PERCENT) / MorpheSettings.STEP_PERCENT);
        slider.setProgress((percent - MorpheSettings.MIN_PERCENT) / MorpheSettings.STEP_PERCENT);
        slider.setProgressTintList(ColorStateList.valueOf(accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(night ? 0xFF3A4352 : 0xFFD3D9E4));
        slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                percent = MorpheSettings.MIN_PERCENT + progress * MorpheSettings.STEP_PERCENT;
                update();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sliderBox.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout ends = new LinearLayout(this);
        ends.setOrientation(LinearLayout.HORIZONTAL);
        ends.setPadding(dp(4), 0, dp(4), 0);
        TextView min = text(MorpheSettings.MIN_PERCENT + "%", 12, textSecondary, false);
        TextView max = text(MorpheSettings.MAX_PERCENT + "%", 12, textSecondary, false);
        max.setGravity(Gravity.END);
        ends.addView(min, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ends.addView(max, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sliderBox.addView(ends);
        card.addView(sliderBox);

        LinearLayout previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.VERTICAL);
        previewBox.setPadding(dp(16), dp(12), dp(16), dp(14));
        previewBox.setBackground(rounded(bg, dp(14)));
        TextView previewLabel = text(t("Preview", "Xem trước"), 12, textSecondary, true);
        previewLabel.setPadding(0, 0, 0, dp(4));
        previewBox.addView(previewLabel);
        preview = text(t("Feed text will look like this.", "Chữ trong bảng tin sẽ trông như thế này."), 16, textPrimary, false);
        previewBox.addView(preview);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.setMargins(dp(16), dp(12), dp(16), 0);
        card.addView(previewBox, previewParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView reset = button(t("Default", "Mặc định"), null, false);
        reset.setOnClickListener(v -> slider.setProgress(
                (MorpheSettings.DEFAULT_PERCENT - MorpheSettings.MIN_PERCENT) / MorpheSettings.STEP_PERCENT));
        TextView apply = button(t("Apply and restart", "Áp dụng và mở lại"), SettingsIcons.REFRESH, true);
        apply.setOnClickListener(v -> applyAndRestart());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        right.setMargins(dp(4), 0, 0, 0);
        buttons.addView(reset, left);
        buttons.addView(apply, right);
        card.addView(buttons);
        return card;
    }

    // ---- Log buttons ----

    private LinearLayout logButtons() {
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView save = button(t("Save to Downloads", "Lưu vào Downloads"), SettingsIcons.DESCRIPTION, false);
        save.setOnClickListener(v -> saveLog());
        TextView clear = button(t("Clear log", "Xoá nhật ký"), SettingsIcons.DELETE, false);
        clear.setOnClickListener(v -> {
            MorpheLog.clear(this);
            Toast.makeText(this, t("Log cleared", "Đã xoá nhật ký"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        left.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.setMargins(dp(4), 0, 0, 0);
        buttons.addView(save, left);
        buttons.addView(clear, right);
        return buttons;
    }

    // ---- Toggle rows ----

    /** Adds a switch row when its patch is in the app. Returns the row, or null when it was left out. */
    private View addToggle(LinearLayout card, final String key, String icon, String title, String summary) {
        if (!PatchedFeatures.allowed(key)) return null;
        if (card.getChildCount() > 0) card.addView(divider());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(ripple(0, 0));
        row.setClickable(true);
        row.setFocusable(true);

        row.addView(iconTile(icon));
        row.addView(textColumn(title, summary));
        final Toggle toggle = new Toggle(this, MorpheSettings.isEnabled(this, key));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(32)));

        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });
        row.setOnClickListener(v -> {
            boolean checked = !toggle.isChecked();
            toggle.setChecked(checked, true);
            MorpheSettings.preferences(this).edit().putBoolean(key, checked).apply();
            if (MorpheSettings.VIDEO_DOWNLOAD.equals(key) && qualityRow != null) {
                qualityRow.animate().alpha(checked ? 1f : 0.5f).setDuration(150).start();
            }
        });
        card.addView(row);
        return row;
    }

    private View iconTile(String icon) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(rounded(accentTint, dp(12)));
        ImageView image = new ImageView(this);
        image.setImageDrawable(SettingsIcons.get(icon, accent));
        tile.addView(image, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
        params.setMargins(0, 0, dp(14), 0);
        tile.setLayoutParams(params);
        return tile;
    }

    private LinearLayout textColumn(String title, String summary) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(text(title, 16, textPrimary, true));
        TextView description = text(summary, 13, textSecondary, false);
        description.setLineSpacing(0, 1.12f);
        description.setPadding(0, dp(2), 0, 0);
        column.addView(description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, dp(12), 0);
        column.setLayoutParams(params);
        return column;
    }

    // ---- Views and drawables ----

    private TextView button(String label, String icon, boolean filled) {
        TextView button = text(label, 14, filled ? onAccent : accent, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable fill = rounded(filled ? accent : accentTint, dp(22));
        GradientDrawable mask = rounded(Color.BLACK, dp(22));
        int color = filled ? ((onAccent & 0x00FFFFFF) | 0x33000000) : ripple;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(color), fill, mask));
        if (icon != null) {
            Drawable drawable = SettingsIcons.get(icon, filled ? onAccent : accent);
            drawable.setBounds(0, 0, dp(18), dp(18));
            button.setCompoundDrawablesRelative(drawable, null, null, null);
            button.setCompoundDrawablePadding(dp(8));
        }
        return button;
    }

    /** Ripple for a row or round button. A radius of 0 gives a plain rectangle. */
    private Drawable ripple(int fill, int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.BLACK);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(ripple), fill == 0 ? null : new ColorDrawable(fill), mask);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        return shape;
    }

    private TextView text(CharSequence content, int sp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    /** Pill switch drawn by hand, so it follows the accent colour on every device. */
    private final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArgbEvaluator evaluator = new ArgbEvaluator();
        private boolean checked;
        private float progress;
        private ValueAnimator animator;

        Toggle(Context context, boolean checked) {
            super(context);
            this.checked = checked;
            this.progress = checked ? 1f : 0f;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean value, boolean animate) {
            checked = value;
            if (animator != null) animator.cancel();
            float target = value ? 1f : 0f;
            if (!animate) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180);
            animator.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float outline = dp(2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((Integer) evaluator.evaluate(progress, trackOff, accent));
            canvas.drawRoundRect(0, 0, w, h, h / 2, h / 2, paint);
            if (progress < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(outline);
                paint.setColor(thumbOff);
                paint.setAlpha(Math.round(255 * (1f - progress)));
                canvas.drawRoundRect(outline / 2, outline / 2, w - outline / 2, h - outline / 2,
                        (h - outline) / 2, (h - outline) / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((Integer) evaluator.evaluate(progress, thumbOff, thumbOn));
            float radius = dp(8) + (dp(12) - dp(8)) * progress;
            float x = h / 2 + (w - h) * progress;
            canvas.drawCircle(x, h / 2, radius, paint);
        }
    }

    private void saveLog() {
        try {
            String path = MorpheLog.saveToDownloads(this);
            Toast.makeText(this, path == null ? t("The log is empty", "Nhật ký đang trống")
                    : t("Saved: ", "Đã lưu: ") + path, Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, t("Could not save: ", "Không lưu được: ") + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void update() {
        value.setText(percent + "%");
        // The system font scale is already applied to sp sizes here.
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * percent / 100f);
    }

    private void applyAndRestart() {
        MorpheSettings.preferences(this).edit().putInt(MorpheSettings.FONT_SCALE_PERCENT, percent).commit();
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null && launch.getComponent() != null) {
            startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        }
        // The font scale is read once at startup, so the process has to restart.
        Runtime.getRuntime().exit(0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
