# AvJoy（avjoy.me）嗅探规则

来源：用户 2026-11-23 01:26 抓包日志 `sniff-log-20261123-012619_11_jsr2.json`（页面 ROYD-368，id 104761）+ 沙箱实测详情页 HTML。

## 结论

- **正片是直链 MP4，不是 HLS。** 页面 `<video>` 的 `<source>` 直接给出 720p MP4，抓包里 `<video>` 的 currentSrc 与之完全一致。
- **growcdnssedge.com 的两条 m3u8 不是正片，别用。** 日志里两条 `media-hls.growcdnssedge.com/b-hls-*/…_480p.m3u8` 的 id（254789176、70032198）与本页 id（104761）完全无关，是站内其他视频的预览/广告，正片判定不能只看“有 m3u8”。
- **不用 Referer。** 全部请求 Referer 为空，直链直接播。

## 正片地址格式

详情页 HTML 里两个 `<source>`（每次抓 token 与过期时间戳都会变，必须现取现用，不可硬编码）：

```
https://media-cdn3.avjoy.me/av1/<token>/<ts>/104761_720p.mp4   # AV1 编码（av01.0.08M.08），排第一
https://media-cdn3.avjoy.me/video/<token>/<ts>/104761_720p.mp4 # 普通 MP4（H.264）
```

- 取法：GET 详情页 HTML，正则取 `<source src="https://media-cdn3.avjoy.me/..._720p.mp4">`。
- 优先用 `/video/` 那条（H.264，兼容性最好）；取不到再用 `/av1/`。
- 播放：交 NativePlayerActivity（ExoPlayer），不带 Referer。
- 取不到回浏览器。

## 与 7mmtv 的区别

- 7mmtv 正片是 HLS（m3u8 + 长时长判定），AvJoy 正片是直链 MP4（`<video>` 标签直读）。
- AvJoy 的 m3u8 是干扰项：只看“有 m3u8”会抓错，必须核对 id 是否与本页一致。
