# 微光阅 Android

原生 View 系统客户端，最低 Android 6（API 23），面向手机、平板与多尺寸墨水屏。

## 架构

- `app`：连接、登录、书库、搜索与格式分流。
- `core`：UniFFI Kotlin 绑定与后台任务边界。
- app 内的 Compose Material 3 主题统一负责颜色、间距、排版和窗口主题。
- `reader-native`：CBZ Canvas、PDF、本地 EPUB/MOBI/AZW3/TXT 解析、原生文本分页与 E-INK 刷新隔离层。
- `rust`：API v4、会话 Cookie、Range、数据模型和错误归一化。

服务端地址必须是根地址，例如 `http://192.168.1.100:7767`。省略协议时默认使用 HTTP；客户端会先请求 `/api/server` 并只接受 API v4。
会话 Cookie 使用 Android Keystore 的 AES-GCM 密钥加密保存；API 相对地址在 Rust 层强制保持同源。

## 本地构建

需要 JDK 21、Android SDK 36、NDK 28、稳定版 Rust 与 `cargo-ndk`：

```bash
cd clients/android/rust
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --locked
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../core/src/main/jniLibs build --release --lib
cd ..
gradle lint test assembleRelease
```

GitHub Actions 会生成独立的 `arm64-v8a` 与 `armeabi-v7a` APK。`android-v*` 标签在配置四个签名 Secret 后发布签名 APK。

## 当前阅读路径

- CBZ：原生 Canvas，当前页完整解码后原子替换，并预取下一页。
- TXT：原生 `StaticLayout` 分页视图。
- PDF：Android 8+ 通过代理文件描述符按 64 KiB HTTP Range 驱动 `PdfRenderer`，只保留 4 个块；Android 6/7 回退到私有临时文件。
- EPUB：下载到受上限管理的缓存后，在设备端解包，按 OPF spine 解析章节并使用原生 `Canvas + StaticLayout` 分页。
- MOBI/AZW3：在设备端解析 PalmDOC 文本记录；HuffDic、DRM 或加密书籍明确报错，不回退 WebView。

Android 客户端不依赖或打包 Web 阅读器。服务端只提供 API 数据流、原文件、封面、分页与进度同步。
下载字体保存在公共 `Downloads/LumosReader/Fonts`；书籍解析缓存位于应用缓存目录，可在设置中统计、限制和清理。

厂商 E-INK API 只允许出现在 `EInkController` 实现中；未知设备安全回退到标准 Android 绘制。
