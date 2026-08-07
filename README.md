# 微光阅 Lumos Reader

面向 NAS 的轻量在线阅读服务。书籍保留在只读挂载目录中，服务端以单个 Go 进程提供书库扫描、按需内容读取、阅读进度、统计、字体管理和内嵌 Web 客户端。

[![Build image](https://github.com/Alumos/LumosReader/actions/workflows/image.yml/badge.svg)](https://github.com/Alumos/LumosReader/actions/workflows/image.yml)

![微光阅书库](docs/preview.png)

## 核心特性

- 支持 EPUB、MOBI、AZW3、PDF、CBZ/ZIP 和 TXT。
- EPUB、MOBI、AZW3 由 foliate-js 按原书语义排版；fixed-layout EPUB 保留 XHTML、SVG、CSS 和书内字体，不转换为图片。
- CBZ/ZIP 才使用图片阅读器，扫描时建立自然排序页索引，阅读时仅解压当前页并维护小型预取窗口。
- PDF.js 使用 64 KiB HTTP Range；正文与书籍资源按需读取，不在服务端生成完整副本。
- Android WebView/Via 兼容层处理 blob iframe、旧版正则语法和 fixed-layout 跨 spread 空白闪屏。
- 阅读进度、定位、阅读时长、书架配置和字体由 SQLite 与独立字体目录保存。
- 莫奈柔彩和 E-INK 黑白主题；首页提供带封面的最近阅读滑动卡片。
- 单镜像同时发布 `linux/amd64` 与 `linux/arm64`。

## 阅读与缓存语义

| 格式 | 阅读器 | 网络与内存策略 |
| --- | --- | --- |
| EPUB | foliate-js reflow/fixed-layout | Range 按需读取；fixed-layout 预取后续 3 节 |
| MOBI / AZW3 | foliate-js | Range 按需读取，不预先下载整本 |
| CBZ / ZIP | 原生图片页阅读器 | 当前单双页加后续 4 页短期窗口 |
| PDF | PDF.js | 64 KiB Range，关闭自动整本预取 |
| TXT | 轻量滚动阅读器 | 单次正文请求，可中止加载 |

正文、漫画页和 Range 响应使用 `Cache-Control: private, no-store`；封面与字体允许浏览器缓存。退出阅读器后释放临时页面与对象 URL。

## Docker Compose 部署

仓库自带的 `compose.yaml` 使用环境变量管理 NAS 路径：

```bash
cp .env.example .env
docker compose pull
docker compose up -d
```

适配群晖目录的示例：

```yaml
services:
  reader:
    image: ghcr.io/alumos/lumosreader:latest
    restart: unless-stopped
    read_only: true
    user: "0:0"
    environment:
      ADDR: ":8080"
      LIBRARY_DIR: "/library"
      DATA_DIR: "/data"
      FONTS_DIR: "/fonts"
      ADMIN_PASSWORD: "${ADMIN_PASSWORD}"
      SCAN_INTERVAL: "15m"
    ports:
      - "7767:8080"
    volumes:
      - "/volume5/漫画:/library/漫画:ro"
      - "/volume6/图书:/library/图书:ro"
      - "./data:/data:rw"
      - "/volume7/字体:/fonts:rw"
    cap_drop:
      - ALL
    security_opt:
      - "no-new-privileges:true"
```

管理密码建议写入同目录的 `.env`，不要提交到仓库：

```dotenv
ADMIN_PASSWORD=请替换为新的强密码
```

字体上传接口直接写入 `FONTS_DIR`。以上配置中，Web 上传的 TTF、OTF、WOFF、WOFF2 会保存到 NAS 的 `/volume7/字体`；从 NAS 外部放入该目录的字体也会在下次打开字体库时实时出现，无需重启容器。单文件上限为 32 MiB，同名文件不会覆盖。

升级镜像：

```bash
docker compose pull
docker compose up -d --force-recreate
```

若服务暴露到公网，请使用 NAS 反向代理、Caddy 或 Nginx 提供 HTTPS。HTTP 更适合可信局域网。

## 服务配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ADDR` | `127.0.0.1:8080` | HTTP 监听地址 |
| `LIBRARY_DIR` | `./library` | 只读书库根目录 |
| `DATA_DIR` | `./data` | SQLite 状态目录 |
| `FONTS_DIR` | `${DATA_DIR}/fonts` | 可写字体目录 |
| `ADMIN_PASSWORD` | 空 | 空值表示无需登录 |
| `SCAN_INTERVAL` | `15m` | 自动扫描间隔；设为 `0s` 可关闭 |

## Android 与第三方客户端协议

客户端只需保存规范化后的服务端根地址，例如 `http://nas.alumos.xyz:7767`，不要保存 Web 页面路径。

推荐连接流程：

1. 规范化 URL，去除末尾 `/`，拒绝其中的用户名、密码、查询参数和片段。
2. `GET /api/server`，验证这是 Lumos Reader 且 `api_version` 可兼容。
3. `GET /api/session`；若需要认证，显示密码页并 `POST /api/session`：`{"password":"..."}`。
4. 持久保存响应中的 HttpOnly `lumos_session` Cookie，并在后续 API、Range、封面和字体请求中发送；会话有效期为 30 天，服务重启后需重新登录。
5. `GET /api/books` 获取书库。相对 URL 必须基于同一个服务端根地址解析。
6. 阅读期间节流写入进度和阅读时长；遇到 `401` 回到登录页，成功后重放幂等请求。

主要 API：

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/server` | 服务发现、API 版本、支持格式 |
| `GET /api/session` / `POST /api/session` | 查询会话 / 密码登录 |
| `DELETE /api/session` | 登出 |
| `GET /api/books` / `GET /api/books/{id}` | 书库与书籍详情 |
| `GET /api/books/{id}/content` | EPUB、MOBI、AZW3、PDF、TXT 内容；支持 Range |
| `GET /api/books/{id}/cover` | 封面 |
| `GET /api/books/{id}/pages` | 仅 CBZ/ZIP 图片页目录 |
| `GET /api/books/{id}/pages/{page}` | 仅 CBZ/ZIP 单页图片 |
| `GET/PUT /api/books/{id}/progress` | 查询/保存进度与 locator |
| `POST /api/books/{id}/reading-time` | 累计阅读秒数 |
| `GET /api/fonts` / `GET /api/fonts/{name}` | 字体列表与下载 |
| `GET /api/stats` | 阅读统计 |

兼容约束：

- `shelf_kind` / `is_comic` 只描述书架分类，不得用于选择渲染器。
- `fixed_layout` 表示 EPUB 固定版式能力；所有 EPUB 都必须走 EPUB 渲染器。
- 只有 `format == "cbz"` 才能调用 `/pages`。
- 内容端点可能返回 `206 Partial Content`，客户端必须正确处理 `Content-Range`。
- 当前 API 版本为 v4。新增字段应按可选字段处理，未知字段应忽略。
- Android 若连接 `http://` 地址，需要明确允许明文流量并提示风险；推荐公网地址使用 HTTPS。

## 本地开发

需要 Go 1.26 与 Node.js 26：

```bash
cd web
npm ci
npm run build
cd ..
go test ./...
go run ./cmd/lumosreader
```

将测试书放入 `library`，访问 <http://127.0.0.1:8080>。未设置 `ADMIN_PASSWORD` 时无需登录。

发布前检查：

```bash
cd web && npm run build && cd ..
go test ./...
go vet ./...
```

推送到 `main` 后，GitHub Actions 自动构建并发布：

```text
ghcr.io/alumos/lumosreader:latest
ghcr.io/alumos/lumosreader:sha-<commit>
```

## 项目结构

```text
cmd/lumosreader/         程序入口
internal/server/         HTTP API、书库、EPUB 元数据、SQLite、运行时
internal/server/testdata Go 标准测试夹具
internal/testutil/       测试辅助程序
web/                     React 前端与内嵌资源
web/build/               foliate-js 构建期兼容补丁
web/src/reader/          各格式阅读器、设置与公共组件
scripts/                 桌面与 Android WebView 回归脚本
docs/                    界面预览
```

`testdata` 位于被测 Go 包内部，是 Go 官方测试目录约定，不会作为普通源码依赖嵌入生产程序。

## 许可证与内容

仓库不包含书籍内容。部署者应确保自己有权存储和阅读挂载目录中的文件。
