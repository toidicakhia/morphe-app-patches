# Kế hoạch port LocketWidget sang Android (Morphe / RVA patch)

> Tài liệu lập kế hoạch, viết bằng tiếng Việt. Cập nhật: 2026-11-28.
> Nguồn nghiên cứu: `D:\LocketWidget` (origin `https://github.com/n0td1n0kh0a/LocketWidget`,
> commit `7059df2a`, 2026-11-26). Mục tiêu: `D:\morphe-app-patches`.

---

## 1. Tóm tắt và phạm vi

Kho `LocketWidget` **không phải là một ứng dụng Android** và cũng không chứa mã vá APK.
Đây là kho tài liệu reverse-engineering kèm bộ công cụ máy chủ MITM / spoofer
(Python, Node, Caddy, mitmproxy). Vì vậy "port sang Android" trong ngữ cảnh này
được hiểu là:

> Chuyển các **hành vi vá** được mô tả và triển khai phía máy chủ trong
> `D:\LocketWidget` thành các **bản vá Morphe/ReVanced** cho ứng dụng
> `com.locket.Locket`, kết hợp với một máy chủ spoofer bên ngoài cho các tính năng
> được kiểm soát phía server.

Phạm vi của tài liệu này là lập kế hoạch; chưa thay đổi mã nguồn nào trong
`D:\morphe-app-patches`.

---

## 2. Hiện trạng

### 2.1 Kho nguồn nghiên cứu (`D:\LocketWidget`)

- Mục tiêu nghiên cứu: Android `com.locket.Locket` **1.247.3 (build 636)** và iOS **2.74.0 (4874)**.
- Kiến trúc Locket (theo `README.md:58-68`):
  - Firebase Auth / backend callables → `idToken` (JWT, ~1 giờ).
  - `api.locketcamera.com` → API JSON, body dạng `{"data": {...}}`.
  - Firestore dự án `locket-4252a` → tài liệu người dùng, danh sách bạn bè.
  - Firebase Storage các bucket `locket-img`, `locket-video`, `locket-rollcall`.
  - Firebase App Check → cổng kiểm soát thao tác ghi.
  - RevenueCat → entitlement `Gold`.
- Thành phần có thể tái sử dụng trực tiếp làm đặc tả vá:
  - `server/proxy_revenuecat.py` — payload giả cho subscriber Gold.
  - `server/proxy_locket_api.py` — chèn `badge`/`is_gold`, đổi ảnh đại diện sang frame,
    thu hoạch header `X-Firebase-AppCheck` tại `GET /__appcheck`.
  - `server/proxy_remoteconfig.py` — trộn `rc_overrides.json` (54 khoá) và
    `rc_overlay_remote.json` (179 khoá) vào Remote Config; bỏ qua
    `camera_filter_config` / `picker_filters`.
  - `server/rc-live-android-1.247.3.json` — bản Remote Config thực tế (268 mục).
  - `rebuild/gen_sgmodule.py` — các đoạn JS tương đương với logic vá ở trên;
    là nguồn gần nhất để chuyển sang hook Hermes.
  - `rebuild/caddy-spoofer-domain.caddy` — có nhắc trực tiếp tới bản vá
    "Spoofer server" trong `morphe-app-patches`.
- Không nên port: mobileconfig, Keychain / App Group, CloudKit, APNS,
  WidgetKit / Live Activities, StoreKit receipt replay, Metal shader, App Attest
  — tất cả là đặc thù iOS và vắng mặt trong registry Android.

### 2.2 Kho bản vá (`D:\morphe-app-patches`)

Đã có hai bản vá cho Locket, nằm trong
`patches/src/main/kotlin/me/toidicakhia/patches/locket/`:

| File | Chức năng |
|---|---|
| `LocketUnlockGoldPatch.kt` | Thay bytecode Hermes của `androidGoldSubscriptionOverride()` bằng `LoadConstTrue; Ret`, biến flag `android_gold_subscription_override` thành `true`. |
| `LocketSpooferServerPatch.kt` | Đổi `extra.api.baseUrl` trong `assets/app.config`, đổi tên khoá RC `functions_domains` → `functions_domainz` trong bundle Hermes và `classes2.dex`, tuỳ chọn chuyển hướng RevenueCat và Remote Config. |

Cả hai đều khai báo `compatibleWith(LOCKET_COMPATIBILITY)`, hiện bị **khoá ở
1.247.1 / versionCode 632** trong `patches/src/main/kotlin/me/toidicakhia/patches/shared/Constants.kt`.
Trong khi đó tài liệu nghiên cứu mô tả **1.247.3 / 636**. Đây là điểm cần xử lý đầu tiên.

Cơ chế đăng ký bản vá: tự động quét jar (`util/PatchListGenerator.kt`), nên khi
thêm một `val ... = resourcePatch(...)` cấp cao thì không cần sửa file đăng ký tập trung.

---

## 3. Mô hình port

Port theo 3 lớp, tách bạch để giảm rủi ro:

1. **Lớp client (bản vá trong repo này)** — sửa `assets/app.config`, bytecode Hermes, dex,
   resource XML. Chỉ nên làm những gì có thể xác minh bằng byte cụ thể trong APK đích.
2. **Lớp máy chủ spoofer (bên ngoài repo này)** — trả payload Gold, frame, caption,
   chèn badge vào `fetchUserV2`/`fetchUsers`, thu hoạch App Check. Bản vá chỉ cần
   **điều hướng lưu lượng** tới máy chủ này.
3. **Lớp không port** — tính năng server-gated thực sự (AI generation, referral rewards,
   ghi badge bởi backend Locket, entitlement RevenueCat thật) và toàn bộ phần iOS-only.

Nguyên tắc: nếu một tính năng bắt buộc phải có máy chủ, bản vá chỉ cung cấp tuỳ chọn
để trỏ tới máy chủ đó; không cố giả lập phía client.

---

## 4. Ánh xạ tính năng

| Khả năng trong `D:\LocketWidget` | Thực tế trên Android 1.247.3 | Cách port |
|---|---|---|
| Chèn entitlement Gold (`proxy_revenuecat.py`, `gen_sgmodule.py`) | Cổng client tồn tại; cổng server thì không | Mở rộng `Unlock Gold` + overlay RC; Gold "thật" vẫn cần spoofer/receipt |
| Overlay Remote Config (`rc_overrides.json`, `rc_overlay_remote.json`) | 51 flag `android_*`; RC trực tiếp khác giá trị mặc định đóng gói | Bản vá mới, bỏ qua `camera_filter_config` / `picker_filters` |
| Chuyển hướng API (`caddy-spoofer-domain.caddy`) | Đã làm một phần | Mở rộng bản vá Spoofer server theo từng host |
| Chèn badge / frame (`proxy_locket_api.py`) | Nằm trong phản hồi `fetchUserV2` / `fetchUsers` | Xử lý ở máy chủ; bản vá chỉ cần route đúng endpoint |
| Thu hoạch App Check (`/__appcheck`) | Không có ký hiệu App Check trong JS bundle; native adapter tự đính kèm; RC trực tiếp `android_appcheck_enabled=true` | Ghi đè flag phía client + tuỳ chọn hook native; server vẫn có thể trả 403 |
| AI frames / `generateFilteredImage`, receipt StoreKit | Chỉ có trên iOS, vắng mặt trong registry Android | Không port |
| Caption / overlay (`getMomentOverlayData` có xác nhận; tên khác cần kiểm tra registry) | Là callable backend | Route qua spoofer; chỉ vá nếu endpoint tồn tại trong 1.247.3 |

---

## 5. Các bản vá đề xuất

### P0 — Căn chỉnh phiên bản (việc bắt buộc làm đầu)

- Cập nhật `LOCKET_COMPATIBILITY` trong
  `patches/src/main/kotlin/me/toidicakhia/patches/shared/Constants.kt` lên
  **1.247.3 / 636**; chỉ giữ 1.247.1 làm `AppTarget` thứ hai nếu fingerprint vẫn khớp.
- Kiểm tra lại với APK đích:
  - chuỗi byte `GOLD_OVERRIDE_METHOD` trong `assets/index.android.bundle`,
  - tính duy nhất của `functions_domains` trong bundle Hermes và `classes2.dex`,
  - bốn hằng chuỗi host trong `classes3.dex` / `classes4.dex`,
  - phiên bản bytecode Hermes 96.
- Hiện không có APK trong workspace. Cần tải đúng bản (ví dụ
  `apkeep -a com.locket.Locket -d apk-pure`, theo `docs/android-re.md`) và đối chiếu
  SHA-256 ghi tại `docs/android-re.md:27-29`.

### P1 — Mở rộng "Unlock Locket Gold"

- File: `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketUnlockGoldPatch.kt`.
- Giữ nguyên stub bytecode hiện có; thêm tuỳ chọn để ép
  `android_feature_gates_override = true` và overlay các khoá RC liên quan Gold
  từ `D:\LocketWidget\server\rc_overrides.json`.
- Làm rõ mô tả bản vá: chỉ mở cổng phía client; AI/referral vẫn cần server.

### P2 — Mở rộng "Spoofer server"

- File: `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketSpooferServerPatch.kt`.
- Thay cặp "URL + 2 toggle" bằng các tuỳ chọn route riêng: API
  (`api.locketcamera.com`), RevenueCat, Remote Config, Cloud Functions,
  CDN (`cdn.locketcamera.com`) và WebSocket nhóm (`wss://api.locketcamera.com/wss_v2/groups`).
- Giữ mặc định an toàn (chỉ API) và kiểm tra đầu vào hiện tại: HTTPS bắt buộc,
  không có path, không có credentials.
- Thêm tuỳ chọn "Trust user certificates" cho triển khai MITM nội bộ; tái dùng
  cách sửa `res/xml/network_security_config.xml` trong
  `patches/.../vtvgo/VtvgoPrivacyPatches.kt:108-139`, nhưng phải xác nhận file này
  thực sự tồn tại trong APK Locket.
- Lưu ý: relay spoofer hiện tại là HTTP-only; route WebSocket chỉ bật khi máy chủ hỗ trợ.

### P3 — "Disable App Check" (bản vá mới)

- File dự kiến: `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketAppCheckPatch.kt`.
- Ghi đè `android_appcheck_enabled` / `app_check_enabled` trong giá trị mặc định RC;
  nếu cần, hook provider attestation native để bỏ header `X-Firebase-AppCheck`.
- Ghi rõ trong mô tả: thao tác ghi/upload do server kiểm soát vẫn có thể bị từ chối.

### P4 — "Remote Config overlay" (bản vá mới)

- File dự kiến: `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketRemoteConfigPatch.kt`.
- Chèn các mục chọn lọc từ `server/rc-live-android-1.247.3.json` và
  `server/rc_overrides.json` vào defaults đóng gói.
- Giữ quy tắc bỏ qua `camera_filter_config` / `picker_filters` để bảng Looks native
  không bị hỏng, kèm bản sửa độ trễ shutter theo `docs/android-re.md:281-414`.

### P5 — "AI / Caption relay" (tuỳ điều kiện)

- Chỉ triển khai sau khi đối chiếu `docs/api-registry.txt` để xác nhận tên callable
  nào thực sự tồn tại trong 1.247.3 (đã xác nhận: `getMomentOverlayData`;
  `generateCaption` / `backendCaptions` cần kiểm tra kỹ).
- Dùng lại cấu trúc phản hồi trong `server/proxy_locket_api.py` và chỉ cần route
  tới spoofer nếu bản thân endpoint đã tồn tại.

### Tái cấu trúc dùng chung

Tách phần lặp lại (magic Hermes, kiểm tra phiên bản, tính lại SHA-1 footer, tiện ích dex)
từ hai bản vá hiện có sang:

- `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketHermes.kt`
- `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketFingerprints.kt`

---

## 6. Cấu trúc file dự kiến

```
patches/src/main/kotlin/me/toidicakhia/patches/locket/
├── LocketHermes.kt                  (mới, dùng chung)
├── LocketFingerprints.kt            (mới, dùng chung)
├── LocketUnlockGoldPatch.kt         (mở rộng — P1)
├── LocketSpooferServerPatch.kt      (mở rộng — P2)
├── LocketAppCheckPatch.kt           (mới — P3)
├── LocketRemoteConfigPatch.kt       (mới — P4)
└── LocketAiRelayPatch.kt            (mới, tuỳ điều kiện — P5)

patches/src/main/kotlin/me/toidicakhia/patches/shared/Constants.kt   (P0)
docs/locket-android-port-plan.md                                    (tài liệu này)
```

Không chỉnh sửa tay `patches-list.json`, `patches-bundle.json` hay bảng README —
chúng được sinh tự động.

---

## 7. Quy trình build và kiểm tra

1. Tải và giải nén đúng bản XAPK 1.247.3; đối chiếu hash với tài liệu gốc.
2. Trích `assets/app.config`, `assets/index.android.bundle`, `classes2.dex` …
   Kiểm tra từng pattern byte xuất hiện **đúng một lần**. Dùng `hbc-decompiler`
   nếu cần xác định điểm hook JS mới.
3. Build: `./gradlew :patches:buildAndroid`
   (JDK 21, Android SDK 36, credentials GitHub Packages — xem README gốc).
4. Sinh lại metadata: `./gradlew :patches:generatePatchesList`, sau đó chạy script
   `python3 .github/scripts/generate_patches_readme.py ...` như hướng dẫn trong README.
5. Áp dụng bằng Morphe CLI/Desktop, cài đặt như một gói **mới** (chữ ký khác bản gốc;
   nên sao lưu dữ liệu trước).
6. Kiểm tra thực tế: UI Gold, giá trị flag RC (qua debug dump), log requests tới
  spoofer, feed/đăng ảnh, và không crash khi khởi động.
7. Commit theo semantic message: `feat(locket): ...`, `fix(locket): ...`.

---

## 8. Rủi ro và giới hạn

- **Bytecode Hermes gắn chặt với từng bản build** — sai pattern là patch fail ngay;
  phải pin phiên bản và ném `PatchException` rõ ràng.
- **Cổng phía server không thể vá từ client**: AI effects, referral, badge do backend
  ghi, trạng thái RevenueCat thật — đều cần spoofer hoặc receipt replay.
- **App Check có thể được enforcing ở server** — ghi đè flag client chưa chắc đủ
  để cho phép ghi/upload.
- **HTTP/3 `alt-svc` cache** có thể làm request đi tắt khỏi spoofer theo từng lần
  (xem `docs/stack-and-operations.md` trong kho nguồn).
- Không port tài sản iOS-only (mobileconfig, Keychain/CloudKit, StoreKit, WidgetKit,
  Metal shader).
- Mục đích là nghiên cứu/giáo dục; APK đã vá dùng chữ ký khác và có thể vi phạm
  điều khoản của Locket.

---

## 9. Quyết định cần có trước khi triển khai

1. Chỉ hỗ trợ **1.247.3**, hay giữ cả **1.247.1 và 1.247.3**?
2. Ưu tiên: **Gold/RC unlock** trước, **spoofer routing** trước, hay
   **App Check / thao tác ghi** trước?
3. Spoofer: tiếp tục yêu cầu **máy chủ bên ngoài**, hay cần thêm tuỳ chọn
   **máy chủ chạy ngay trên thiết bị** (phạm vi lớn hơn đáng kể)?

---

## 10. Phụ lục — đường dẫn nguồn quan trọng

```
D:\LocketWidget\README.md
D:\LocketWidget\docs\android-re.md
D:\LocketWidget\docs\locket-android-schemas-1.247.3.md
D:\LocketWidget\docs\auth-config-and-wire.md
D:\LocketWidget\docs\api-registry.txt
D:\LocketWidget\docs\features-and-protocols.md
D:\LocketWidget\docs\stack-and-operations.md
D:\LocketWidget\server\proxy_locket_api.py
D:\LocketWidget\server\proxy_revenuecat.py
D:\LocketWidget\server\proxy_remoteconfig.py
D:\LocketWidget\server\rc_overrides.json
D:\LocketWidget\server\rc-live-android-1.247.3.json
D:\LocketWidget\rebuild\gen_sgmodule.py
D:\LocketWidget\rebuild\caddy-spoofer-domain.caddy
```

---

## 11. Tiến độ triển khai (cập nhật 2026-11-28)

### Đã hoàn thành — P0

- Tải được XAPK **1.247.3** và **1.247.1** qua `apkeep -d apk-pure` (mục đích nghiên cứu).
  Base APK của 1.247.3 có SHA-256
  `31c68eb32b62da951620f914231afc52af8880564aadadc432489b2b21b064de`, khớp chính xác
  giá trị ghi trong `docs/android-re.md` của kho nguồn.
- Chứng thư ký của cả hai bản đều có SHA-256
  `f7dd2c755cbbdf342b216c453e76e2ffcb34d688e5f91499bec0341648cbcb16`, khớp
  `LOCKET_COMPATIBILITY.signatures`.
- Xác minh trên cả hai bản:
  - Hermes bytecode version **96**, magic hợp lệ;
  - `baseUrl` trong `assets/app.config` xuất hiện **đúng 1 lần**;
  - `functions_domains` xuất hiện **đúng 1 lần** trong bundle Hermes và **đúng 1 lần**
    trong `classes2.dex`;
  - host RevenueCat / `8-lives-cat.io` nằm trong `classes4.dex`, host Remote Config
    nằm trong `classes3.dex` — đúng giả định của `LocketSpooferServerPatch`.
- **Phát hiện quan trọng**: bytecode của `androidGoldSubscriptionOverride()` ở 1.247.3
  **khác** với 1.247.1, nên bản vá Gold cũ sẽ fail nếu chỉ nâng phiên bản.
  Pattern mới (1.247.3):
  `2900002e000002360100015c76005102010037010202c56d730091d353000102005c00`
  (35 byte, xuất hiện đúng 1 lần). Pattern cũ giữ lại cho 1.247.1.
- Đã mô phỏng bản vá trên cả hai bundle thật, tính lại SHA-1 footer, rồi dùng
  `hbc-decompiler` để kiểm tra: hàm được decompile thành `r0 = true; return r0;` —
  chứng tỏ stub `LoadConstTrue; Ret` đúng opcode (0x78 / 0x5C).

### Thay đổi mã nguồn đã áp dụng

- `patches/.../shared/Constants.kt`: bổ sung `AppTarget` **1.247.3 / 636**, giữ
  1.247.1 / 632; cập nhật comment giải thích hai fingerprint Gold.
- `patches/.../locket/LocketUnlockGoldPatch.kt`: chấp nhận cả hai pattern bytecode,
  stub pattern khớp, thông báo lỗi rõ ràng nếu gặp bản khác.
- `patches/.../locket/LocketHermes.kt` (mới): gom magic/version/SHA-1/`indexOfSequence`
  dùng chung từ hai bản vá Locket.
- `patches/.../locket/LocketSpooferServerPatch.kt`: dùng helper dùng chung, bỏ mã trùng.

### Build (đã hoàn thành)

- Dùng token GitHub CLI (`gh auth token`, tài khoản `toidicakhia`, có scope
  `write:packages`) làm `GITHUB_TOKEN` cho Gradle; không lưu token vào repo.
- Cài Android SDK vào `C:\Android\android-sdk` (đúng `ANDROID_HOME` có sẵn):
  command-line tools + `platforms;android-36` + `build-tools;36.0.0` + `platform-tools`.
- `./gradlew :patches:compileKotlin` và `./gradlew :patches:buildAndroid` đều
  **BUILD SUCCESSFUL**. Artifact: `patches/build/libs/patches-1.8.0.mpp` (~1,4 MB).
- Đã sinh lại `patches-list.json` và bảng README (cả hai bản vá Locket giờ liệt kê
  1.247.3 và 1.247.1); script README báo lỗi khi in ký tự ✅ ra console cp1252,
  nhưng file README đã được ghi đúng trước đó.

### Bước tiếp theo

- Tiếp tục P1–P4 theo thứ tự ưu tiên đã nêu ở mục 9.
- Chưa cài đặt thử trên thiết bị; cần apply `patches-1.8.0.mpp` qua Morphe
  CLI/Desktop và smoke-test khi có thiết bị.
