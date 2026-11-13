# GeckoView 迁移记录（feat/geckoview，2026-11-14）

目标：WebView + 自建 ECH/DoH 基建 → GeckoView（火狐内核）做基底，
Gecko 原生自带 DoH + ECH，不再配网络基建。包大一点没关系。

## 进度（2026-11-14）
- 步骤 1–5 全部做完：构建配置、Gecko 运行时、嵌页播放器、搜索改明文、
  删基建。已提交，待 CI 绿。
- 本地无 Android SDK / Java，无法本地编译；以 CI 为准。
- 未迁 WebView 清单见最下「未迁 / 已知取舍」。

## 已确认的事实（动手前查过）

- 分支 feat/geckoview，基于 e3c7971（vc149），跟踪 fork/feat/geckoview。
  origin 是上游 fekilooo（只读），push 一律 `git push fork`。
- 参考实现 hyper-browser：GeckoView 151.0.20260525130955，
  DoH provider 与 bootstrap IP 池照抄 BrowserProfileStore.kt
  （DOH_BOOTSTRAP_POOL，默认 172.64.146.66；echEnabled 跟 doh 走）。
- 发布 asset 名是 app-debug.apk（CI 产出，无 ABI 分片）。本次加 arm64
  分片后产出 app-debug-arm64.apk，与交付链接口径一致。
- /api/play/ 已有明文兜底：postFormEch 失败就走 postForm（明文），
  所以删 ECH 后 /api/play/ 走明文 postForm，逻辑不用改。

## 步骤

### 1. 构建配置（app/build.gradle.kts）
- 加 `org.mozilla.geckoview:geckoview:151.0.20260525130955`。
- 加 ABI 分片：只出 arm64-v8a（GeckoView 原生库大，单 ABI 控制体积，
  产出 app-debug-arm64.apk）。
- 删 `org.conscrypt:conscrypt-android` 与 `org.chromium.net:cronet-embedded`
 （ECH/Cronet 基建随 ConscryptEch/EchDoh/EchH3Router 一起删）。
- versionCode 149 → 150，versionName 1.2.1 → 1.2.2-gecko。
- 其余不动：Kotlin 1.9 / AGP 8.2 / compileSdk 34 / minSdk 24
  （GeckoView 151 要求 minSdk 24+，现状已满足）。

### 2. Gecko 运行时（新文件 gecko/）
- `GeckoRuntimeProvider`：全局单例 GeckoRuntime；DoH 用
  GeckoRuntimeSettings（TRR_ONLY + provider URL + bootstrap IP 随机取池），
  ECH 用 config YAML prefs（network.dns.echconfig.enabled 等，跟 doh 走）。
  抄 hyper-browser 的做法，简化：不要通知 delegate、不要严格隐私那套。
- `JavhdGeckoExtension`：嵌頁播放器专用的内置 WebExtension
  （resource://android/assets/javhd-gecko/），做三件事：
  ① webRequest 拦 11 个广告域名（与 JavhdEmbedPlayerActivity.AD_HOSTS 同步），
  tsyndicate.com（VAST）永远不拦；
  ② content script 轮询点跳过（.jw-skip → Skip 文字 → jwplayer().skipAd()，
  与原来 evaluateJavascript 同一顺序）；
  ③ content script 读 video 元素状态，回传给 Activity 记播放数据。

### 3. 嵌頁播放器（JavhdEmbedPlayerActivity）
- WebView → GeckoView + GeckoSession；嵌頁地址直接 loadUri，
  Gecko 原生 ECH/DoH，不再走 SearchHttpFetcher ECH 预载。
- UA 保持 EMBED_UA（Pixel 7 手机 UA），经 session settings 设 override。
- 跳过轮询与播放状态读取改走扩展消息，不再 evaluateJavascript。

### 4. 搜索抓取改明文 OkHttp（SearchHttpFetcher）
- 删 EchDoh/EchH3Router/ConscryptEch 依赖，改 plain OkHttp，
  保留：响应结构、CookieManager 双向同步、host allowlist、2MB 体 cap、
  超时、取消、retryAfter/cloudflare 标记。
- postFormEch 删掉，调用点直接用 postForm（明文，已有兜底）。
- trySniffVideoCandidates 里 `EchDoh.configFor(host) == null` 的嗅探门去掉，
  改为直接走 SearchHttpFetcher（明文）取详情页。

### 5. 删基建
- 删 ConscryptEch.kt、EchDoh.kt、EchH3Router.kt。
- 删 proguard-rules.pro 里 Conscrypt keep（只为 ECH 服务）。
- JavBrowserApplication 去掉 EchH3Router.init。
- fetchWebViewResource 调用点随 WebView 迁移一并清理（见下）。

## 未迁 / 已知取舍（待后续，不悄悄降级）

- MainActivity（7238 行，主浏览器）、UnifiedSearchActivity（3152 行）、
  FullscreenInternalPlayerActivity（3418 行）仍用 WebView；它们的
  shouldInterceptRequest 里 EchH3Router.fetchWebViewResource 调用改为
  直接放行（返回 null，走 WebView 默认加载）。这意味着这几个 WebView
  不再走 ECH 拦截，明文 SNI 加载在用户网络上可能被 RST/403。
  后续：把这几个主 WebView 也迁到 GeckoView。
- SearchHttpFetcher 改明文 OkHttp 后，搜索 HTML 抓取不再隐藏 SNI；
  若用户网络上明文 SNI 被 RST，搜索会受影响，后续应把搜索抓取也
  走 GeckoView（原生 ECH/DoH）。
- NativePlayerActivity（ExoPlayer 直播直链）的 videoEchClient 改为
  plain OkHttp，不再走 ECH；直链视频在要求 ECH 的 CDN 上可能 403。
  javhd 主路已是嵌頁播放器（GeckoView），不受影响。
- Cookie：OkHttp 路仍用 android.webkit.CookieManager，
  GeckoView 用自己的 cookie 仓，两边不互通。搜索与 /api/play/
  全程走 OkHttp，cookie 自洽；浏览器里登录态与搜索不共享，
  后续如需打通再处理。
- uBlock Origin 本次未装：嵌頁播放器用自写扩展拦广告已够用；
  主浏览器迁到 GeckoView 时再按 hyper-browser 做法装 uBlock。
