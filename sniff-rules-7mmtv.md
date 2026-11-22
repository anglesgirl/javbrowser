# 7mmtv 嗅探规则（由 sniff-log-20261122-183619 导出，2026-11-22）

来源：用户在 7mmtv 播放页（https://7mmtv.sx/zh/censored_content/208210/HUNTC-649.html）用抓包日志扩展抓 35 秒，88 条请求、6 个 video 元素。

## 结论
- 主视频是 HLS（MSE/HLS.js 拉流），不是 <video src> 直链。嗅探规则必须匹配 XHR 的 .m3u8，不能只读 video 标签。
- 正片地址：`https://media-hls.growcdnssedge.com/b-hls-XX/<id>/<id>_240p.m3u8?playlistType=standard&preferredVide...`
  - 分片是 fMP4：`.../<id>_240p_h264_<seq>_<hash>_<ts>.mp4`，初始化段 `.../<id>_240p_h264_init_<hash>.mp4`
  - content-type：`application/vnd.apple.mpegurl`（.m3u8）、`video/mp4`（分片）
  - 日志里出现两个 id（288797343、211551244），都是 240p；用“m3u8 + 长时长”挑正片，不要只看第一个。
- **不需要 Referer**：88 条请求里 Referer 全是空（扩展的 header 抓取是好的，User-Agent 88/88 都有），播放器拉流本来就不带 Referer，嗅到的 .m3u8 可直接播。
- 另一个 HLS 源：`embeds02gfdiu0pzo1el0e9lwgacz1cq.streamsuperpro.com/<hash>/32/2/<id>...`（.m3u8），是嵌页播放器（StreamSuperPro）的路子，和 growcdnssedge 是两条线。
- 排除项：页面 <video> 元素全是 10 秒预览（`n39s.1024cdn.sx/censored/*.mp4`、`n39s.1024cdn.sx/tmv/*_9s.mp4`）和一个 webm 广告（`gif.7mmtv.sx/hgame/*.webm`），不是正片。规则要排除短时长（≤10 秒）预览。

## 嗅探规则（给 javbrowser）
1. 匹配 `.m3u8`（content-type 含 mpegurl 或 URL 以 .m3u8 结尾，忽略 query 串）→ 候选正片。
2. 优先 `media-hls.growcdnssedge.com` 的 .m3u8；其次 streamsuperpro 的 .m3u8。
3. 排除 `.mp4` 预览（n39s.1024cdn.sx 的 10 秒短片）和 .webm 广告。
4. 不用补 Referer（源站不要求）。
5. 正片判定沿用“m3u8 + 长时长”：多个 .m3u8 时取播放时长最长的那路。
