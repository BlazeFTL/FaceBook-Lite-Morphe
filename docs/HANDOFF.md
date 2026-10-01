# Ghi chú bàn giao: font trong feed Facebook Lite

Tài liệu này dành cho người làm tiếp. Nó ghi lại những gì đã làm, những gì đã kiểm chứng,
những gì **chưa** kiểm chứng, và việc cần làm tiếp. Tất cả tên lớp bị làm rối (`X.0eF`, `X.C0KY`...)
là của **Facebook Lite 530.0.0.8.106** (arm64-v8a, APK từ APKMirror). Mỗi bản app mới, tên sẽ đổi.

## 1. Trạng thái hiện tại (v1.1.0, đã phát hành)

| Patch | Làm gì | Đã kiểm chứng |
| --- | --- | --- |
| Use system font | Chặn các file `.ttf/.otf` Meta tải về (`files/Optimistic_*.ttf`, `Instagram*`, `emoji_font.ttf`...) bằng cách thay mỗi file bằng một thư mục cùng tên | Có: vá bằng Morphe Manager, app chạy, không tải được font nào |
| Install beside Meta's apps | Đổi tên 2 permission dùng chung với Facebook/Messenger (`FB_APP_COMMUNICATION`, `receiver.permission.ACCESS`) để hết lỗi `INSTALL_FAILED_DUPLICATE_PERMISSION` | Có: cài được cạnh app khai báo cùng permission khác khoá ký, và trên OPPO có cài Facebook |

**Vấn đề còn lại:** trên OPPO CPH2825 (ColorOS, Android 16, font hệ thống là OPPO Sans),
phần **tin nhắn** dùng OPPO Sans, nhưng **feed vẫn hiện Roboto** (chữ hẹp, chữ `g` khác).
Patch "Use system font" không tác động tới feed.

## 2. Những gì đã kiểm chứng

### 2.1. Code chính của app không nằm trong `classes.dex`
- Dex chính chỉ có khoảng 850 lớp (bộ nạp). Phần còn lại nằm trong
  `assets/secondary-program-dex-jars/store-0.dex.spo`, nén bằng **Superpack** (định dạng riêng của Meta).
- Morphe **chỉ sửa được dex chính**. Không patch thẳng được code font/feed.
- File spo được giải nén lúc `ClientApplicationSplittedShell.attachBaseContext()`:
  - Android 10+ (megazip): ghi ra `/data/data/com.facebook.lite/dex/z-<sha>.zip` (chứa `classes.dex` + `classes2.dex`).
  - Android < 10: ghi ra `dex/prog-<hash>.dex`.
- Muốn lấy dex để đọc: dùng máy ảo image **Google APIs** (không phải Google Play) để `adb root`,
  mở app một lần rồi `adb pull /data/data/com.facebook.lite/dex/`.
- Loader chấp nhận dex **không nén**: bỏ dòng `.superpack_extension spo` trong `metadata.txt`, đặt
  `secondary-1.dex`, `secondary-2.dex` vào assets. Hash trong `metadata.txt` không được kiểm tra,
  chỉ dùng để đặt tên file (đổi hash là app tự giải nén lại). Cách này đã chạy (sửa dex trực tiếp, cài bằng
  APKLab), nhưng không đưa vào Morphe được vì phải phát tán dex của Meta.

### 2.2. Font hệ thống trên OPPO
Đã chạy app thử [research/font-test-app](../research/font-test-app) trên OPPO. **Tất cả** cách chọn font đều ra
**OPPO Sans**: TextView mặc định, `Typeface.DEFAULT`, `Typeface.create(DEFAULT, 400/700, false)`,
`create(DEFAULT, BOLD)`, `create("sans-serif")`, và cả `create("roboto")`.
`/system/etc/fonts.xml` trên máy map `sans-serif` → `SysFont-Regular.ttf` cho mọi weight.

**Kết luận:** feed **không** vẽ bằng font hệ thống của Android. Nếu nó dùng bất kỳ API Typeface nào kể trên thì đã ra OPPO Sans.

### 2.3. Không có code nào nạp file Roboto trực tiếp
Trong dex phụ không có chuỗi `/system/fonts`, `Roboto-Regular.ttf`... Các chỗ tạo Typeface từ file chỉ có
`X.0vN` (font Meta tải về, đã chặn) và `createFromAsset("fonts/...")` cho vài TextView.

## 3. Giả thuyết chính (chưa kiểm chứng xong): feed vẽ bằng "server font"

Facebook Lite kế thừa engine J2ME (`com.moblica.common.xmob`). Server gửi **đường viền vector của từng ký tự**.
App tự vẽ các ký tự đó vào một atlas bitmap rồi `drawBitmap` từng ký tự ra màn hình. Font server gửi trông giống Roboto.

Các lớp liên quan (bản 530.0.0.8.106):

| Lớp | Vai trò |
| --- | --- |
| `X.0KY` (jadx: `C0KY`) | Giải mã dữ liệu font server. Kiểu mã hoá 1/2/3/4/5/6/8. Kiểu 7 = "client side fonts" nhưng ném `IllegalStateException("clientFontCreator cannot be null for client side fonts")` |
| `X.0eE` (`C06230eE`) | Cache ký tự: `A00(char)` → `X.1In` (atlas + rect), gọi rasterizer khi chưa có |
| `X.0eF` (`C06240eF`) | **Rasterizer**: `A03(byte[] glyphData, char c)` vẽ path vector vào atlas, trả `X.1In`. Bên ngoài chỉ dùng constructor `(LX/0e1;Lcom/moblica/common/xmob/ui/WindowManager;IIIIZZZ)V` và `A03([BC)LX/1In;` |
| `X.0e1`, `X.1Ik`, `X.1Im`, `X.1In`, `X.0eG` | Atlas: `0e1.A00` (atlas hiện tại), `0e1.A02` (lock), `1Ik.A01(w,h)` cấp ô, `1Ik.A00().A04` là Canvas của atlas |
| `X.1Ih` (`AbstractC22971Ih`, redex: `TextAreaDrawingHelper`) | Vẽ từng ký tự từ atlas ra màn hình |
| `X.0JP` (`FontCacheManager`), `X.0K5` (`FontFIFOCache`) | Cache font ra đĩa: `/sdcard/Android/data/com.facebook.lite/cache/font/` (file tên số, khoảng 30KB) |

Header dữ liệu ký tự: byte 0 = kiểu, byte 1 = độ rộng (signed). Nếu kiểu 8 thì bỏ 4 byte.
Nếu kiểu 3 hoặc 6 thì tiếp theo là `short` big-endian = chiều cao. Mặc định chiều cao = tham số thứ 3 của constructor.

Bằng chứng ủng hộ: chuỗi `"Error initializing font cache for offline feed items"`, `"Wait for font cache"`.
Khi đăng nhập, app gửi lên server các font đang cache và `pref_key_font_experiment_hash` / `pref_key_new_font_experiment_data`
(lớp `X.0GP`, `X.0JR`): server quyết định gửi font nào.

## 4. Hướng sửa đang thử: thay lớp `X.0eF` (class shadowing)

Ý tưởng: đặt một lớp **cùng tên** `X.0eF` vào dex của APK (`classes3.dex`). Nó dùng lại cách cấp ô atlas
nhưng vẽ ký tự bằng `canvas.drawText` với font hệ thống, giữ nguyên kích thước ô server gửi
(nên dàn dòng không đổi). Mã nguồn: [research/glyph-rasterizer/src/X/ZZeF.java](../research/glyph-rasterizer/src/X/ZZeF.java).

### Thứ tự nạp lớp (đọc từ code, chưa chứng minh lúc chạy)
- App cài ClassLoader riêng `X.09K` làm **parent** của PathClassLoader (`ClassLoader.parent` được set bằng reflection),
  kể cả trên Android 10+ (`C09K.A01(context)` trong `X.07m` / megazip).
- `09K.loadClass` chỉ tách tên framework (`android.*`, `java.*`...) sang parent. Lớp app đi vào `findClass`, duyệt mảng
  `A02 = [null, A08 (dex của APK), A07, A03 (dex phụ thêm bằng A05)]`.
  Tức là **dex của APK được tìm trước dex phụ**, nên về lý thuyết thay lớp được.

### Kết quả thử
1. **Máy ảo** (API 37, image 16k): thay `0eF`, `1fj`, `2cc` có ghi log. **Không thấy log nào và feed không đổi.**
   Chưa phân biệt được giữa (a) thay lớp không ăn và (b) máy ảo không dùng các lớp đó.
   Trên máy ảo feed vốn đã là Roboto (= font hệ thống của máy ảo), nên nhìn ảnh chụp không phân biệt được.
2. Đã dựng bản "probe": smali gốc của `0vN` và `0eF` chỉ thêm `Log.i("FbLiteProbe", ...)`, để biết chắc lớp thay có được nạp không.
   Máy ảo tắt trước khi chạy xong. **Trên OPPO, ColorOS ẩn log của app** (chỉ thấy log framework), nên không dùng log được.
3. **Đang dở:** trên OPPO đã cài bản thử có `0eF` vẽ bằng font hệ thống (cộng 2 patch v1.1.0), đăng nhập xong,
   nhưng mất kết nối adb trước khi chụp màn hình. **Việc đầu tiên: mở feed trên OPPO xem chữ đã thành OPPO Sans chưa.**

### Việc cần làm tiếp
1. Xem feed trên OPPO với bản thử ở mục 3.
   - Feed thành OPPO Sans: giả thuyết và cách thay lớp đều đúng, sang bước 2.
   - Vẫn Roboto: hoặc thay lớp không ăn, hoặc feed không qua `0eF`. Kiểm tra bằng cách cho `0eF` thay thế vẽ chữ
     **màu đỏ** hoặc font serif (thấy được bằng mắt, không cần log). Nếu không đổi, thử chỗ khác: `X.1Ih.A00(...)`
     (vẽ từng ký tự), hoặc kiểm tra lúc chạy bằng reflection xem `Class.forName("X.0eF").getDeclaredMethods()`
     có `drawChar` không (gọi từ một extension chạy trễ).
2. Chỉnh hiển thị: cỡ chữ đang ước lượng `height * 0.82`, baseline căn giữa ascent/descent, nén ngang nếu rộng hơn ô.
   Cờ `bold` đang đoán là tham số boolean đầu tiên của constructor (`A06`, chọn vector font 15/14). Cần so trên máy thật.
3. Đưa vào Morphe:
   - Extension viết bằng Java nên **không đặt được tên lớp bắt đầu bằng số** (`0eF`). Cách làm: trong patch, lấy
     `ClassDef` của lớp extension (ví dụ `app/fblite/extension/ServerGlyphRasterizer`), tạo bản sao đổi type thành `LX/0eF;`
     và các tham chiếu `LX/ZZ*;` sang tên thật (dexlib2 `ImmutableClassDef` + rewriter), rồi thêm vào `classes`.
   - Không fingerprint được lớp trong dex phụ (patcher không thấy). Tên `X.0eF`... phải **khai cứng theo từng phiên bản**,
     nên giới hạn `compatibleWith` đúng version.
   - Sau khi thay `0eF` thì "Use system font" vẫn giữ (nó lo các font TTF).
4. Hướng khác nếu thay lớp không được: (a) can thiệp lúc đăng nhập (bitmask khả năng `X.0JT.A09` trong `X.0GP`,
   hoặc font experiment hash) để server gửi kiểu 7 (client font). Rủi ro: gửi dữ liệu lạ lên server. (b) Hook native
   (LSPlant/Pine) trong extension. Nặng và dễ vỡ.

## 5. Bẫy đã gặp
- **Morphe inline smali:** `attachBaseContext` có `.locals 31`, nên `p1` là `v32` và `invoke-static {p1}` báo NPE trong
  `InlineSmaliCompiler`. Phải dùng `invoke-static/range { p1 .. p1 }` (đã sửa ở v1.0.1).
- **APK Morphe xuất ra đã có `classes2.dex`.** Đè lên là crash `NoClassDefFoundError: X.0D7`. Dùng `classes3.dex`.
- **Lần đầu thêm nguồn** trong Morphe Manager khi CI chưa phát hành xong thì Manager lưu `patches-bundle.json` rỗng,
  báo "nguồn không tên, thiếu metadata". Xoá nguồn rồi thêm lại.
- **CI:** đẩy commit `feat:`/`fix:` lên `main` là semantic-release tự build `.mpp` và phát hành. Commit `docs:`/`chore:` không phát hành.
  Cần có nhánh `dev` (bước backmerge).
- Bản thử ký bằng debug key khác khoá Morphe Manager, nên phải gỡ app trước khi cài (mất đăng nhập).
- Máy ảo image Google Play không root được. Dùng image Google APIs. Image API 37 16k thỉnh thoảng tự tắt.
- Trên máy ảo `ro.debuggable=1` nhưng jdb không attach được vào Facebook Lite (handshake fail).

## 6. Dựng lại prototype
```
cd research/glyph-rasterizer
ANDROID_HOME=~/Library/Android/sdk APKTOOL_JAR=~/.apklab/apktool_3.0.2.jar ./build.sh
UBER_APK_SIGNER_JAR=~/.apklab/uber-apk-signer-1.3.0.jar ./make-test-apk.sh /path/to/morphe-patched.apk
adb uninstall com.facebook.lite && adb install out/test-aligned-debugSigned.apk
```
APK đã vá bằng Morphe lấy từ Morphe Manager (nút Save), hoặc trên máy có root:
`/data/user/0/app.morphe.manager/app_ui_ephemeral/installer/output.apk`.
