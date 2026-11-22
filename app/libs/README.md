# echproxy.aar — Go 前置代理（预编译）

本机 DoH/ECH HTTP 代理，源码在 `~/workspace/co3/ech/echproxy`
（Go module `github.com/anglesgirl/ech-proxy-android`），用 gomobile
导出给 Android，arm64，minSdk 26。App 里的接线见
`EchProxyManager.kt`（后台起代理 + ProxyController 指 WebView 到它）。

## 重编（沙箱里已验证过的流程）

前置：Go 1.26、Android SDK（platform-tools、platforms;android-36、
build-tools;36.0.0）、NDK r27c。沙箱里 SDK 在 `~/workspace/android-sdk`，
gomobile 在 `~/go/bin/gomobile`。

```sh
export ANDROID_HOME=~/workspace/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
export PATH=~/workspace/go/go/bin:~/go/bin:$PATH
export GOPATH=~/go
cd ~/workspace/co3/ech
# 首次需把 gomobile 加进 module（已做过，go.mod 里有 tool 指令）：
#   go get -tool golang.org/x/mobile/cmd/gobind
gomobile bind -androidapi 26 -target=android/arm64 \
  -o echproxy.aar github.com/anglesgirl/ech-proxy-android/echproxy
```

注意：`-androidapi 26` 必加（NDK r27 不支持默认的 API 16）。
编好后把 `echproxy.aar` 拷到本目录覆盖，提交即可。
