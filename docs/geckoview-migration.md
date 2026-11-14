# GeckoView 迁移记录（feat/geckoview，2026-11-14）

目标：WebView + 自建 ECH/DoH 基建 → GeckoView（火狐内核）做基底，
Gecko 原生自带 DoH + ECH，不再配网络基建。包大一点没关系。

## 进度（2026-11-14）
- 步骤 1–5 全部做完：构建配置、Gecko 运行时、嵌页播放器、搜索改明文、
  删基建。已提交，待 CI 绿。
- 本地无 Android SDK / Java，无法本地编译；以 CI 为准。
- 未迁 WebView 清单见最下「未迁 / 已知取舍」。

## 交付（2026-11-14 18:13）
- CI 全绿（run 43479846070，build + release 均为 success）。
- Release：build-20261113-7c09650，asset app-debug-arm64.apk，
  109,046,249 字节（约 104MB，旧包 33MB；GeckoView 原生库占大头）。
- 镜像：https://gh.anglesya.win/anglesgirl/javbrowser/releases/download/build-20261113-7c09650/app-debug-arm64.apk
  （实测 200，字节数与 release 一致）。
- 验包（androguard）：包名 com.example.javbrowser.ech、versionCode 150、
  versionName 1.2.2-gecko、minSdk 26、targetSdk 34；
  含 lib/arm64-v8a/libxul.so（GeckoView）与 assets/javhd-gecko/manifest.json。
- 提交：373e315（迁移本体）→ 4a445ab（Mozilla Maven）→ 6e32986
  （compileSdk 35/AGP 8.9.1/Gradle 8.11.1）→ 637d941（compileSdk 36）
  → 5095ff4（minSdk 26）→ f8bbdf4（Kotlin 2.3.20/Java 17）
  → 41602a2（compilerOptions DSL）→ e0d16da（2 处 Kotlin 2.3 修）
  → 7c09650（CI 分片 APK 改名）。

## 工具链抬升（GeckoView 151 强制，非自愿升级）
- Mozilla Maven 仓库（GeckoView 不在 Maven Central）。
- compileSdk 34 → 36（media3 1.9.3 要 35，androidx.core 1.18.0 要 36）。
- AGP 8.2.0 → 8.9.1（core 1.18.0 要求），Gradle 8.2 → 8.11.1。
- minSdk 24 → 26（GeckoView 151 manifest 强制）。
- Kotlin 1.9.20 → 2.3.20、Java 1.8 → 17（新依赖用 Kotlin 2.1/2.3
  metadata，Kotlin 1.9 读不了）；kotlinOptions 改 compilerOptions DSL。
- targetSdk 仍 34。media3/core 的声明版本未改（1.4.1/1.12.0），
  Gradle 已解析到传递进来的 1.9.3/1.18.0。

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
  （2026-11-14 1.2.3-gecko：MainActivity 的 navigateToUrl 已改走
  GeckoBrowserActivity，首页点外站不再经 WebView；MainActivity 本体
  的首页本地 HTML 仍用 WebView。）
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

## 1.2.3-gecko（2026-11-14）：修用户真机反馈两项

### 问题 1（急）：首页直接点网站就断网
- 根因：MainActivity 仍用 WebView，迁移时 shouldInterceptRequest 的
  ECH 拦截改成放行（明文 SNI），用户网络上直接被 RST。首页“直接
  前往”的站（navigateToUrl：JavDB、JavTrailers、Stripchat、Pornhub、
  XVideos 等无原生解析器的站）走 WebView.loadUrl，明文 SNI 被 RST。
- 修法：新建 GeckoBrowserActivity（通用 GeckoView 浏览器，顶栏地址
  + 关闭，Gecko 原生 DoH + ECH，追踪防护开着，不装 javhd 扩展），
  MainActivity.navigateToUrl 改走它启动。搜索与播放主流程不动。
  MainActivity 本体的 WebView（首页本地 HTML）仍在，只是不再用它
  打开外站。

### 问题 2：嗅探 7mmtv 只能得到广告
- 根因：VideoPageSniffer 是静态 HTML 解析，没有 7mmtv 专属解析器，
  退到通用 video 标签/宽松匹配，只能捡到广告；正片是 JS 动态加载，
  静态 HTML 里根本没有。
- 修法（用户定：进入播放页面的时候开始嗅探）：新建
  GeckoSniffActivity，把详情/播放页装进 GeckoView（Gecko 原生 DoH
  + ECH，JS 正常跑、播放器正常发请求），内置扩展 background 用
  webRequest 实时观察播放器实际请求的 .m3u8/.mp4（非阻塞，同款
  广告过滤：ad/ads/preview/trailer/thumb/sample/snapshot 段、
  11 广告域、tsyndicate.com VAST 都丢弃），经 native 消息回传
  （JavhdGeckoExtension.attachMediaSniffer，扩展安装晚到时
  ensureInstalled 回调里也给 mediaCandidate 会话补挂 delegate）。
  UnifiedSearchActivity.openResult：静态候选为空时（只得广告或
  什么都没有）改走 launchLiveSniff；回包过同一套
  filterSniffCandidates（worker 线程，m3u8 时长取不到返回 null、
  候选照留），有正片开选择器，空手回落原有逻辑（无广告→浏览器，
  有广告→广告选择器）。Referer 仍是详情页。/api/play/ 桌面 UA
  铁律、搜索解析器、选集均未动。
- 嗅探窗口：首个候选到后等 3 秒收尾，20 秒超时空手返回；可点 ✕
  或返回键提前结束。
