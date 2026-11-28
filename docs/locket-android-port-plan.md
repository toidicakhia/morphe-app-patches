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

Hiện có bốn bản vá cho Locket, nằm trong
`patches/src/main/kotlin/me/toidicakhia/patches/locket/`:

| File | Chức năng |
|---|---|
| `LocketUnlockGoldPatch.kt` | Stub `androidGoldSubscriptionOverride()` (và tuỳ chọn `androidFeatureGatesOverride()`) thành `LoadConstTrue; Ret`. |
| `LocketSpooferServerPatch.kt` | Chuyển hướng host API / RevenueCat / Remote Config / CDN / groups WebSocket; đổi tên khoá RC `functions_domains`; tuỳ chọn tin user CA. |
| `LocketAppCheckPatch.kt` | Stub ba wrapper khởi tạo App Check của React Native Firebase. |
| `LocketRemoteConfigPatch.kt` | Ép giá trị một số flag RC phía client (recaptcha, history ads, Gold button, beta features). |
| `LocketBypassSslPinningPatch.kt` | Tin user CA, stub OkHttp `CertificatePinner` và `HostnameVerifier` để debug qua proxy. |
| `LocketSpoofFirebaseCertPatch.kt` | Trả về SHA-1 chữ ký gốc cho Firebase (sửa lỗi 403 sau khi đóng gói lại). |

Tất cả đều khai báo `compatibleWith(LOCKET_COMPATIBILITY)`, hiện đã được chuyển
sang **1.247.3 / versionCode 636** trong
`patches/src/main/kotlin/me/toidicakhia/patches/shared/Constants.kt`, khớp với bản
mà tài liệu nghiên cứu phân tích. Phiên bản 1.247.1 đã bị loại bỏ khỏi danh sách hỗ trợ.

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

## 5. Các bản vá đã triển khai

### P0 — Căn chỉnh phiên bản (xong)

- `LOCKET_COMPATIBILITY` trong `patches/.../shared/Constants.kt` chỉ còn
  **1.247.3 / 636**; target 1.247.1 đã bị xoá.
- Đã tải XAPK 1.247.3 thật và xác minh: SHA-256 base APK khớp tài liệu nghiên cứu,
  chứng thư ký khớp hash đã pin, Hermes v96, `baseUrl` / `functions_domains` và
  các host trong dex đều xuất hiện đúng một lần.

### P1 — Unlock Locket Gold (xong)

- `LocketUnlockGoldPatch.kt` thêm tuỳ chọn `forceFeatureGates` (mặc định bật):
  stub luôn bytecode của `androidFeatureGatesOverride()` thành `return true`.
- Việc overlay tuỳ ý các khoá từ `rc_overrides.json` không thực hiện ở P1; nó được
  thay bằng P4 (ép getter) và P2 (điều hướng RC tới spoofer để lấy giá trị bất kỳ).

### P2 — Spoofer server (xong)

- `LocketSpooferServerPatch.kt` có các tuỳ chọn: `spooferServer`,
  `routeRevenueCat`, `routeRemoteConfig`, `routeCdn`, `routeGroupsWebsocket`,
  `trustUserCertificates`. Mặc định chỉ chuyển hướng API.
- Ngoài `app.config`, bản vá còn đổi host `https://api.locketcamera.com` trong
  `classes2.dex`, host RevenueCat trong `classes4.dex` và host Remote Config trong
  `classes3.dex`.
- APK Locket **không có** `res/xml/network_security_config.xml`; bản vá tạo file này
  và thêm `android:networkSecurityConfig` vào `<application>` khi bật tuỳ chọn tin user CA.
- Relay spoofer hiện tại là HTTP-only; route WebSocket chỉ nên bật khi server hỗ trợ wss.

### P3 — Disable App Check (xong)

- Không tồn tại flag RC `android_appcheck_enabled`. Bản vá mới
  `LocketAppCheckPatch.kt` stub ba wrapper khởi tạo: `initAppCheck`,
  `initAppCheckRecaptcha` và `initializeAppCheck`.
- Các hàm lấy token được giữ nguyên vì phải trả về chuỗi, không phải boolean.
- Đây là biện pháp best effort: backend vẫn có thể tự enforce `X-Firebase-AppCheck`.

### P4 — Remote Config overrides (xong)

- RC defaults không nằm trong SLP object mà được gán lúc chạy bằng
  `PutNewOwnById`, nên không thể đảo một tag boolean trong file. Bản vá mới
  `LocketRemoteConfigPatch.kt` stub trực tiếp các getter đã xác minh pattern:
  sáu flag recaptcha → false, `android_history_ads_enabled` → false,
  `android_subscribe_gold_button_enabled` → true, `beta_features_enabled` → true.
- Giá trị RC tuỳ ý (kể cả `camera_filter_config` / `picker_filters`) vẫn nên lấy
  qua máy chủ spoofer ở P2 thay vì đưa vào patch.

### P5 — AI / Caption relay (xong thông qua P2)

- Trong 1.247.3 chỉ có `getMomentOverlayData` là callable liên quan AI/caption;
  `generateCaption`, `backendCaptions`, `createFilter`, `generateFilteredImage`
  đều có số lần xuất hiện **0** trong bundle và registry.
- Vì tất cả callable đi qua `api.baseUrl`, P2 đã chuyển hướng `getMomentOverlayData`
  tới spoofer. Không cần bản vá riêng cho P5.

### Dùng chung

- `patches/src/main/kotlin/me/toidicakhia/patches/locket/LocketHermes.kt` chứa
  `requireHermes96`, `rehashHermes`, `indexOfSequence` và `stubHermesBoolGetter`.
- `LocketFingerprints.kt` không được tạo: mỗi bản vá giữ fingerprint riêng ngay
  trong file của nó cho dễ đối chiếu với từng bản APK.

---

## 6. Cấu trúc file dự kiến

```
patches/src/main/kotlin/me/toidicakhia/patches/locket/
├── LocketHermes.kt                  (dùng chung)
├── LocketNetworkSecurity.kt         (dùng chung — user CA)
├── LocketFingerprints.kt            (fingerprint smali)
├── LocketUnlockGoldPatch.kt         (mở rộng — P1)
├── LocketSpooferServerPatch.kt      (mở rộng — P2)
├── LocketAppCheckPatch.kt           (P3)
├── LocketRemoteConfigPatch.kt       (P4)
├── LocketBypassSslPinningPatch.kt   (debug TLS)
└── LocketSpoofFirebaseCertPatch.kt  (sửa 403 Firebase)

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

## 9. Quyết định

- **Đã quyết định**: chỉ hỗ trợ **1.247.3**; bỏ hoàn toàn 1.247.1.
- **Đã triển khai**: P0–P5 (Gold + feature gates, spoofer routing, App Check,
  RC overrides, AI/caption relay thông qua routing).
- Còn cần chốt:
  1. Có cần máy chủ spoofer **chạy ngay trên thiết bị** không (phạm vi lớn hơn nhiều)?
  2. Chạy smoke-test trên thiết bị thật (đã xác minh bằng CLI + Android SDK,
     chưa cài trên máy).

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

- Tải XAPK **1.247.3** qua `apkeep -d apk-pure` (mục đích nghiên cứu). Base APK có
  SHA-256 `31c68eb32b62da951620f914231afc52af8880564aadadc432489b2b21b064de`,
  khớp chính xác giá trị ghi trong `docs/android-re.md` của kho nguồn.
- Chứng thư ký có SHA-256
  `f7dd2c755cbbdf342b216c453e76e2ffcb34d688e5f91499bec0341648cbcb16`, khớp
  `LOCKET_COMPATIBILITY.signatures`.
- Xác minh trên bản 1.247.3:
  - Hermes bytecode version **96**, magic hợp lệ;
  - `baseUrl` trong `assets/app.config` xuất hiện **đúng 1 lần**;
  - `functions_domains` xuất hiện **đúng 1 lần** trong bundle Hermes và **đúng 1 lần**
    trong `classes2.dex`;
  - host RevenueCat / `8-lives-cat.io` nằm trong `classes4.dex`, host Remote Config
    nằm trong `classes3.dex` — đúng giả định của `LocketSpooferServerPatch`.
- **Phát hiện quan trọng**: bytecode của `androidGoldSubscriptionOverride()` ở 1.247.3
  khác với bản cũ, nên bản vá Gold cũ sẽ fail nếu chỉ nâng phiên bản. Pattern mới:
  `2900002e000002360100015c76005102010037010202c56d730091d353000102005c00`
  (35 byte, xuất hiện đúng 1 lần). Pattern cũ đã được bỏ; chỉ 1.247.3 được hỗ trợ.
- Đã mô phỏng bản vá trên bundle thật, tính lại SHA-1 footer, rồi dùng
  `hbc-decompiler` để kiểm tra: hàm được decompile thành `r0 = true; return r0;` —
  chứng tỏ stub `LoadConstTrue; Ret` đúng opcode (0x78 / 0x5C).

### P1–P5 (đã hoàn thành)

- **P1**: `Unlock Locket Gold` có thêm `forceFeatureGates` (mặc định bật) stub
  `androidFeatureGatesOverride()`.
- **P2**: `Spoofer server` có thêm route riêng cho RevenueCat, Remote Config, CDN,
  groups WebSocket, đổi luôn host API trong `classes2.dex`, và tạo
  `network_security_config.xml` khi bật tin user CA.
- **P3**: bản vá mới `Disable App Check` stub ba wrapper khởi tạo App Check;
  không đụng vào hàm lấy token.
- **P4**: bản vá mới `Remote Config overrides` ép 6 flag recaptcha + history ads
  thành false, Gold button + beta features thành true.
- **P5**: chỉ `getMomentOverlayData` tồn tại trong 1.247.3; đã được chuyển hướng
  sẵn qua API baseUrl ở P2. Không cần patch riêng.

### Thay đổi mã nguồn đã áp dụng

- `patches/.../shared/Constants.kt`: chỉ còn `AppTarget` **1.247.3 / 636**.
- `patches/.../locket/LocketHermes.kt` *(mới)*: helper Hermes dùng chung, gồm
  `stubHermesBoolGetter()` với opcode `0x78` (true) / `0x79` (false).
- `patches/.../locket/LocketUnlockGoldPatch.kt`: pattern 1.247.3 + tuỳ chọn feature gates.
- `patches/.../locket/LocketSpooferServerPatch.kt`: routing theo từng host.
- `patches/.../locket/LocketAppCheckPatch.kt` *(mới)*.
- `patches/.../locket/LocketRemoteConfigPatch.kt` *(mới)*.

### Build và apply (đã hoàn thành)

- Dùng token GitHub CLI (`gh auth token`, tài khoản `toidicakhia`, có scope
  `write:packages`) làm `GITHUB_TOKEN` cho Gradle; không lưu token vào repo.
- Cài Android SDK vào `C:\Android\android-sdk` (đúng `ANDROID_HOME` có sẵn):
  command-line tools + `platforms;android-36` + `build-tools;36.0.0` + `platform-tools`.
- `./gradlew :patches:compileKotlin` và `./gradlew :patches:buildAndroid` đều
  **BUILD SUCCESSFUL**. Artifact: `patches/build/libs/patches-1.8.0.mpp` (~1,4 MB).
- Đã sinh lại `patches-list.json` và bảng README (giờ có 4 bản vá Locket, chỉ
  hỗ trợ **1.247.3**); script README báo lỗi khi in ✅ ra console cp1252 nhưng file
  đã được ghi đúng trước đó.

### Kiểm chứng apply

- Tải `morphe-desktop-2.4.0-all.jar` và apply **cả 4 bản vá với mọi tuỳ chọn bật**
  vào XAPK 1.247.3 thật, với `--validate --verify-with-sdk` → **thành công**.
- Giải nén APK đã vá và kiểm tra:
  - `app.config`: `baseUrl`, `groupsWebsocketUrl`, `defaultBasePath` đều trỏ tới spoofer;
  - dex: API host trong `classes2`, RevenueCat trong `classes4`, Remote Config
    trong `classes3` đều được thay; `functions_domains` biến mất;
  - `network_security_config.xml` tồn tại và manifest có `android:networkSecurityConfig`;
  - tất cả 13 hàm đã stub có opcode đầu `0x78` (true) hoặc `0x79` (false) đúng kỳ vọng.

### Patch debug TLS (đã thêm)

- Locket 1.247.3 **không tự cấu hình pin chứng chỉ** (không có `CertificatePinner.add`,
  không có custom `TrustManager`, không có `sha256/` pins trong dex/assets; OkHttp 5
  chỉ dùng trust store mặc định). Vì vậy rào cản thực tế khi MITM là thiếu user CA.
- Bản vá `Bypass SSL Pinning` (mặc định tắt):
  - tạo `network_security_config.xml` tin user CA với `overridePins="true"` và gắn
    vào manifest (helper dùng chung `LocketNetworkSecurity.kt`, cũng dùng cho tuỳ chọn
    tương tự trong bản vá Spoofer);
  - stub `Lokhttp3/CertificatePinner;b(String,Function0)V` → `return-void`;
  - stub `Lokhttp3/internal/tls/OkHostnameVerifier;verify(String,SSLSession)Z` → `true`.
- Đã apply riêng bản vá này vào XAPK thật: **Applied: Bypass SSL Pinning**, không lỗi
  fingerprint. `--verify-with-sdk` báo các cảnh báo có sẵn của AppsFlyer/Sentry/Amazon
  do class nằm ở split dex khác — không phải lỗi do bản vá. Kiểm tra dex vá thấy
  cả hai method đã có stub đúng.

### Sửa lỗi 403 Firebase (đã thêm)

- Khi debug qua HTTP Toolkit, request
  `POST firebaseinstallations.googleapis.com/v1/projects/locket-4252a/installations`
  trả **403** vì APK đã được Morphe ký lại: header `X-Android-Cert` mang SHA-1 mới
  (`16A8FFE6…`) thay vì chữ ký gốc (`187A27D3…`) đã đăng ký với Firebase.
- Bản vá `Spoof Firebase signing certificate` (mặc định bật, có tuỳ chọn
  `originalCertSha1`) thay 5 method tính hash chứng chỉ trong `classes3.dex` bằng
  hằng `187A27D3D7364A044307F56E66230F973DCCD5B7`:
  `LKd/c;.g()` (Firebase Installations), `...remoteconfig.e.l()`,
  `...ConfigFetchHttpClient.i()`, `LKc/p;.d()` (App Check) và
  `...firebase-auth-api.zzaen.zza()`.
- Đã apply riêng bản vá vào XAPK thật: **Applied: Spoof Firebase signing certificate**;
  dexdump xác nhận cả 5 method đều bắt đầu bằng
  `const-string v0, "187A27D3..." ; return-object v0`.

### Bước tiếp theo

- Smoke-test trên thiết bị thật: UI Gold, flag RC, log request tới spoofer, đăng ảnh.
- Kiểm tra lại request Firebase Installations trên HTTP Toolkit sau khi cài APK vá.
- Quyết định có cần spoofer chạy ngay trên thiết bị hay không.
