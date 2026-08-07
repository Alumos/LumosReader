# 微光阅 Lumos Reader

把 NAS 中的 EPUB、MOBI、AZW3、PDF、CBZ 和 TXT 作为只读书库，通过浏览器在线阅读。服务端是单个 Go 进程，内嵌 React 前端；SQLite 仅保存书架、进度与阅读统计。

![微光阅书库](docs/preview.png)

## 特性

- EPUB、MOBI、AZW3 使用 foliate-js 原生排版；固定版式 EPUB 保留原始 XHTML、SVG、CSS 和书内字体，不转换成图片。
- CBZ/ZIP 使用轻量图片阅读器，扫描时建立页索引，阅读时只解压当前页并预载少量后续页面。
- PDF.js 使用 64 KB Range 分块读取；TXT 提供可定制的滚动排版。
- 内容请求支持 HTTP Range，不会在服务端或浏览器持久存储完整书籍副本。
- 阅读进度、章节定位、阅读时长、书架配置和服务端字体统一由同一服务提供。
- 莫奈柔彩与 E-INK 界面主题；阅读器有独立排版模板、字体、单双页和阅读方向设置。

## 项目结构

```text
cmd/lumosreader/       可执行程序入口
internal/server/       书库扫描、EPUB 元数据、HTTP API、SQLite
web/                   React 前端与内嵌资源入口
web/src/reader/        各格式阅读器、设置和兼容层
scripts/               浏览器端到端检查
docs/                  界面预览
```

测试夹具位于 `internal/server/testdata`。`testdata` 是 Go 的标准测试目录约定，不会被打包为普通源码依赖。

## 本地开发

需要 Go 1.26 和 Node.js 26：

```bash
cd web
npm ci
npm run build
cd ..
mkdir -p library
go run ./cmd/lumosreader
```

将测试书籍放入 `library`，然后访问 <http://127.0.0.1:8080>。默认不设置密码。

## Docker Compose

```bash
cp .env.example .env
# 设置书库目录和 ADMIN_PASSWORD
docker compose pull
docker compose up -d
```

仓库中的 `compose.yaml` 提供漫画和图书两个只读挂载示例。服务默认只监听宿主机 `127.0.0.1:8080`，建议通过 Caddy、Nginx 或 NAS 反向代理提供 HTTPS。

字体目录通过 `FONTS_DIR=/fonts` 与 `FONT_DIR_HOST` 挂载解耦。例如设置 `FONT_DIR_HOST=/volume7/字体` 后，Web 上传的 TTF、OTF、WOFF 和 WOFF2 会直接写入该 NAS 目录；字体库每次打开时实时读取目录内容，无需重启服务。

## 在线读取策略

- EPUB/MOBI/AZW3：用兼容 `File.slice()` 的远程文件对象按需请求 ZIP 目录、章节和资源。
- 固定版式 EPUB：由 foliate-js fixed-layout 渲染，保留书籍语义和排版；仅短暂预取后续 3 节。
- CBZ/ZIP：服务端扫描时缓存自然排序的页目录；客户端保留当前单双页及后续 4 页的短期窗口。
- PDF：关闭自动整本预取，以 64 KB Range 加载交叉引用和当前页。
- 封面与字体可浏览器缓存；正文、漫画页和 Range 响应使用 `private, no-store`。

## API

`GET /api/server` 返回 API 版本、认证要求与支持格式。登录后使用 `GET /api/books` 获取书库；书籍正文、封面、漫画页、字体、进度与统计均由同一服务端地址提供。
