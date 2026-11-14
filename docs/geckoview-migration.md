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

## 交付 1.2.3-gecko（2026-11-14 01:01）
- 提交：cddc16a（两项修复本体）→ 9812e9b（修 CI 编译错误 3 处：
  onLocationChange 4 参、canGoBack 用 onCanGoBack 维护、
  item.code orEmpty()）。
- CI 全绿（run 43537080365，build + release 均为 success）。
- Release：build-20261114-9812e9b，asset app-debug-arm64.apk，
  109,056,173 字节。
- 镜像：https://gh.anglesya.win/anglesgirl/javbrowser/releases/download/build-20261114-9812e9b/app-debug-arm64.apk
  （实测 200，字节数与 release 一致）。
- 验包（androguard）：包名 com.example.javbrowser.ech、versionCode
  151、versionName 1.2.3-gecko、minSdk 26、targetSdk 34；
  含 libxul.so（GeckoView）与 assets/javhd-gecko/manifest.json。
- 待真机验：①首页点 JavDB/JavTrailers/Stripchat/Pornhub/XVideos
  能正常加载（GeckoView，不再断网）；②7mmtv 搜详情→静态只得广
  告→自动进播放页实时嗅探→选择器里出现正片地址→能播。

## 白屏修 1.2.4-gecko（2026-11-14）
- 用户真机：首页点 JavDB，GeckoBrowserActivity 打开，顶栏显示
  https://javdb.com/，内容区全白；当时状态栏速率 0.75 KB/s。
- 根因（不是渲染搭错）：与能播的嵌頁播放器逐项核对过——同一
  Runtime、同一 open → setSession → loadUri 顺序；布局是标准顶栏
  + weight=1 的 GeckoView；追踪防护只拦第三方追踪器，不拦主文档；
  默认 Firefox UA 不至于让 javdb.com 空白。真正的问题是加载慢/
  失败时零反馈：页面在极慢网络下极慢或失败（RST/超时），旧版没
  有任何加载进度，失败只有一个 2 秒就消失的 Toast——慢和失败看
  起来都是白屏。
- 修法（只改 GeckoBrowserActivity）：①可见进度条（onProgressChange
  0–100）+“正在加载…”状态行；②NavigationDelegate.onLoadError
  拿 WebRequestError 真实错误码（已核对 GeckoView javadoc：
  onLoadError 返回 GeckoResult<String>，返回 null 停掉加载；
  code 常量如 ERROR_UNKNOWN_HOST / ERROR_NET_TIMEOUT /
  ERROR_NET_RESET / ERROR_CONNECTION_REFUSED 等），翻译成人话附
  错误码，显示在失败页上；③失败页 + 重试按钮（重载当前地址）；
  ④30 秒加载超时也进失败页；⑤onPageStop(success=false) 只作
  兜底（失败页未显示时才显示通用原因，不覆盖 onLoadError 的具体
  原因）。追踪防护保持开（浏览器广告拦截靠它），不装 javhd 扩展，
  不改 UA；搜索、播放、嗅探、/api/play/ 桌面 UA、VAST 放行均未动。

## 白屏根治 1.2.5-gecko（2026-11-14）
- 用户真机：1.2.4 仍是白屏，而且“啥都没有”——没有进度条、没有
  “正在加载…”、没有失败页、没有错误码。进度条只在 onPageStart 里
  出现，30 秒超时也只在 showLoading() 里才计时，说明 onPageStart
  根本没触发：loadUri 在 session 还没真正打开时就发出去了，加载
  被静默丢弃，什么回调都不走。
- 关键 API 纠正（已核对 mozilla-central 源码）：GeckoView 151 里
  GeckoSession.open() 返回 void，没有 GeckoResult 可链（老版本才
  返回 GeckoResult）。open() 文档说“Most GeckoSession methods only
  take effect on an open session, and are queued until the session is
  opened here”，但实测 loadUri 还是丢了。等价做法：open 后立刻把
  加载 UI 亮出来（用户从第 0 秒就看到进度条，不再是白屏），再延
  时 500ms 发 loadUri，给原生 session 打开留时间；另加 5 秒“未开
  始加载”看门狗：onPageStart 5 秒不来就自动重调一次 loadUri（只
  一次），再 5 秒还没动静就直接进失败页写明原因（“页面没有开始加
  载：浏览器会话未就绪，加载请求被丢弃”+ 地址 + 重试按钮）。
- 追踪防护也跟嵌頁播放器/嗅探统一关掉（useTrackingProtection =
  false）：Disconnect 清单可能误拦站点关键资源，页面能打开优先于
  浏览器挡广告；三个 Gecko 活动配置一致，少一个变量。
- 三种结局都有可见反馈：真加载出来 / 加载失败有错误码 / 加载压
  根没开始也有原因，不再无限白屏。搜索、播放、嗅探、/api/play/
  桌面 UA、VAST 放行均未动。

## 白屏根治 1.2.6-gecko（2026-11-14）
- 用户真机：1.2.5 失败页终于出来了，写的是“页面没有开始加载：
  浏览器会话未就绪，加载请求被丢弃”——延时 500ms + 5 秒看门狗
  重调一次都没让 onPageStart 触发。而且看门狗第一跳时
  session.isOpen 已是 true（否则会先报“会话未打开”）：session
  是打开的，但加载就是不开始。不是单纯时序不够。
- 根因（已核对 GeckoView 151 的 mozilla-central 源码，
  GeckoSession.java / GeckoView.java）：两个静默丢弃的关卡。
  ① open() 是异步的：isOpen() 只表示 Java 层 mWindow 已建好，
  原生 Window.open 要等 GeckoThread.PROFILE_READY 才执行；
  loadUri() 发出的 GeckoView:LoadUri 事件进 NativeQueue 排队，
  内核没就绪就一直排着，不回调、不报错。② setActive(true) 只
  由 Surface 触发：GeckoView.setSession() 只在 view 已挂载时才
  acquire display；onAttachedToWindow() 也只 acquire；真正调
  session.setActive(true) 的是底层 Surface 的 onSurfaceChanged /
  acquire（mValid 置真后才调）。javbrowser 从没显式调过
  setActive，全靠 Surface 路径激活——而 hyper-browser 是显式调
  的（GeckoSessionController.setVisible/focusForUserInteraction：
  setActive(true) + setFocused(true) + PRIORITY_HIGH）。
- 修法（只改 GeckoBrowserActivity + MainActivity 预热一行）：
  ① setSession 后立刻显式 setActive(true) + setFocused(true) +
  setPriorityHint(PRIORITY_HIGH)，onResume() 再断言一次，
  onPause() 置 inactive——不依赖 Surface 路径；②首个 loadUri
  改走 geckoView.post（挂载 + 布局完成后再发，保证视图已有真实
  尺寸/Surface），另加 1 秒兜底延时（只发一次）；③
  MainActivity.onCreate 里 decorView.post 预热 GeckoRuntime，让
  内核在首页停留期间就开始初始化，到点网站时已 PROFILE_READY；
  ④ ContentDelegate.onCrash/onKill 进失败页（内容进程死了也不再
  静默）；⑤失败页加“自检”按钮：用同一 runtime 新开探测 session
  加载 about:blank（不走网络），5 秒内 onPageStart 来了就是内核
  与会话正常、问题在目标网址或网络，没来就是内核或运行环境异常，
  定位写进失败页；失败页同时显示诊断快照（会话是否打开 / 视图
  是否挂载 / 视图有会话 / 视图尺寸）。搜索、播放、嗅探、
  /api/play/ 桌面 UA、VAST 放行均未动。

## 1.2.7-gecko：自检 about:blank 也加载不了——逐行对照 hyper-browser

- 用户真机决定性结果：1.2.6 失败页诊断快照全对（会话已打开=true，
  视图已挂载=true，视图有会话=true，视图尺寸=1280x2357），但自检
  （新会话加载 about:blank）5 秒内也不触发 onPageStart。about:blank
  不走网络/DNS/ECH，说明 GeckoView 内核根本不处理加载，不是网络
  问题，不是时序问题。1.2.2 的“播放可以”可能是原生播放器
  （Media3/OkHttp），不是 GeckoView 嵌頁播放器——GeckoView 可能
  在这个 App 里从来就没工作过。
- 逐行对照结果（javbrowser vs hyper-browser，同一台手机、同一
  GeckoView 151.0.20260525130955）：DoH provider URL（同款 Gateway）、
  bootstrap 池（同 5 个 IP）、TRR ONLY、ECH prefs、config 文件内容、
  ContentBlocking、runtime 创建方式、INTERNET 权限、arm64 splits、
  APK 内含 libxul.so + omni.ja + dex——全部一致。唯一的实质差异：
  javbrowser 的 app/build.gradle.kts 设了 packaging.jniLibs.
  useLegacyPackaging = true（.so 全压缩、安装时解压），hyper-browser
  没设（默认未压缩、直接从 APK 加载）。该 workaround 只针对
  AGP <= 8.5 的 16KB 对齐问题，javbrowser 已是 AGP 8.9.1，不再需要。
- 修法：删掉 useLegacyPackaging，与 hyper-browser 对齐。另加
  GeckoMinimalTestActivity（独立进程 :geckotest，默认 runtime——
  不写 config、不设 DoH/ECH，只加载 about:blank，8 秒后出结论），
  入口在 GeckoBrowserActivity 失败页“最小测试”按钮：若默认设置
  下 about:blank 能加载，问题在主进程配置；若也加载不了，问题在
  更底层（打包 / native 库 / 运行环境），与 config 无关。

## 1.2.8-gecko（vc156，2026-11-14 晚）——最小测试按钮点了没反应
- 用户真机 18:18 报：1.2.7 仍是同款“会话未就绪，加载请求被丢弃”，
  自检 about:blank 5 秒无动静——删 useLegacyPackaging 没修好，
  打包不是根因。失败页“最小测试”按钮点了没反应。
- 根因：GeckoMinimalTestActivity 在 manifest 里带
  android:process=":geckotest"，独立进程启动时
  JavBrowserApplication.onCreate() 会再跑一遍，调
  AppTelemetry.initialize() 初始化 Firebase Analytics 与
  Crashlytics；Firebase 默认不是多进程安全的，在次要进程里
  初始化会崩/挂，导致 :geckotest 进程起不来、Activity 出不来。
- 修法：①删掉 manifest 里的 android:process=":geckotest"，最小
  测试改回主进程（仍用全新默认 runtime，照样能二分 config 还是
  更底层）；②JavBrowserApplication.onCreate() 加进程守卫，非主
  进程时跳过 AppTelemetry.initialize() 与生命周期回调注册
  （Application.getProcessName()，API 28 以下走 ActivityManager
  回退），防以后再有独立进程 Activity 踩同一个坑。
- 待用户真机验：点“最小测试”应进测试页，8 秒后出结论并截图发回。

## 1.2.9-gecko（vc157，2026-11-14 晚）——猫抓嗅探核心集成
- 用户真机 19:00 报：嗅探页里正片已经播了，嗅探没抓到，跳回“选择
  播放地址”只剩 36 条 n39s.1024cdn.sx 疑似广告 mp4。根因是旧嗅探
  只认一招：webRequest onBeforeRequest 里 URL 以 .m3u8/.mp4 结尾。
  带签名/不带后缀的正片地址、以及 blob:/MSE 播放（网络请求里只有
  .ts 分片、没有可播地址）全都漏掉；另有收尾 bug：首个候选（常是
  广告）到后 3 秒就收尾，正片在 VAST 跳过后才来、被截断。
- 做法：集成猫抓（CatCatch，https://github.com/xifangczy/cat-catch，
  GPL-3.0；许可证全文在 app/src/main/assets/javhd-gecko/
  CAT_CATCH_LICENSE，background.js/content.js 头有来源声明）。只
  集成嗅探核心，不集成它的弹窗 UI 与下载功能（GeckoView 没有扩展
  弹窗/下载 UI，我们也不需要——已有自己的“选择播放地址”与播放
  逻辑）；m3u8.js 的解析/合并也不移植，Media3/ExoPlayer 原生播
  HLS（含 AES-128，播放器自己取密钥），我们只要地址。
- 移植的三信号（background.js，任一命中即上报 mediaCandidate，
  仍过同一套广告过滤，VAST 与 11 广告域拦截原样保留）：
  ① URL 后缀：.m3u8/.mp4/.mpd（路径末尾才算）；
  ② 响应 Content-Type：onResponseStarted 读响应头，video/*、
  audio/*、application/vnd.apple.mpegurl、application/x-mpegurl、
  application/dash+xml；
  ③ 浏览器资源类型：details.type == "media"（<video>/<audio>
  元素发起的请求）。
  另加 background 内去重（同一 URL 只报一次，500 条清一次）。
- 内容脚本（content.js，matches 由两个 javhd 嵌頁域名放宽到
  <all_urls>；VAST 跳过与 videoState 上报仍只在 javhd 嵌頁域名
  跑）：MutationObserver + 2 秒轮询读全部 <video>/<audio> 与
  <source> 的 currentSrc/src（http(s) 才上报，blob: 不可播不报），
  另在 playing 事件报 videoPlaying 信号（正片真的开始播了）。
- 收尾逻辑（GeckoSniffActivity）：只有非广告候选到后才开始 3 秒
  收尾；videoPlaying 到时有候选立刻收尾、无候选延长 10 秒；回包
  时非广告排前面、广告沉底，去重仍由 linkedSetOf 保证。下游
  UnifiedSearchActivity 的 filterSniffCandidates（广告过滤/折叠/
  m3u8 时长）未动。
- 待用户真机验：进嗅探页播正片，看正片地址是否被抓到、不再只有
  36 条广告。
