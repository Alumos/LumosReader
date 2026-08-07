import { writeFile } from "node:fs/promises";

const targets = await fetch("http://127.0.0.1:9222/json/list").then((response) => response.json());
const socket = new WebSocket(targets.find((target) => target.type === "page").webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});

let id = 0;
const pending = new Map();
const ranges = [];
const fontDownloads = [];
const runtimeExceptions = [];
socket.addEventListener("message", ({ data }) => {
  const message = JSON.parse(data);
  if (message.id) {
    const task = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) task.reject(new Error(`${task.method} ${task.expression ?? ""}: ${message.error.message}`));
    else task.resolve(message.result);
  } else if (message.method === "Network.responseReceived") {
    const response = message.params.response;
    if (response.status === 206) ranges.push({
      contentRange: response.headers["Content-Range"] ?? response.headers["content-range"],
      cacheControl: response.headers["Cache-Control"] ?? response.headers["cache-control"] ?? "",
    });
    if (response.url.includes("/api/fonts/")) fontDownloads.push({ url: response.url, status: response.status });
  } else if (message.method === "Runtime.exceptionThrown") {
    runtimeExceptions.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
  }
});

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const requestID = ++id;
  pending.set(requestID, { resolve, reject, method, expression: params.expression });
  socket.send(JSON.stringify({ id: requestID, method, params }));
});
const evaluate = async (expression) => {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
};
const waitFor = async (expression, timeout = 10000) => {
  const started = Date.now();
  while (!(await evaluate(`Boolean(${expression})`))) {
    if (Date.now() - started > timeout) throw new Error(`Timed out: ${expression}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};
const showFont = async (name, root) => {
  const pageCount = await evaluate(`Number(${root}.querySelector('.font-pagination span').textContent.split('/')[1])`);
  for (let page = 0; page < pageCount; page++) {
    if (await evaluate(`[...${root}.querySelectorAll('.font-list > button')].some(x => x.textContent.includes(${JSON.stringify(name)}))`)) return;
    await evaluate(`${root}.querySelector('[aria-label="下一页字体"]').click()`);
    await waitFor(`${root}.querySelector('.font-pagination span').textContent.startsWith('${page + 2} /')`);
  }
  throw new Error(`Font not found: ${name}`);
};
const firstFontPage = async (root) => {
  while (!await evaluate(`${root}.querySelector('[aria-label="上一页字体"]').disabled`)) {
    await evaluate(`${root}.querySelector('[aria-label="上一页字体"]').click()`);
  }
};

await send("Page.enable");
await send("Runtime.enable");
await send("Network.enable");
await send("Page.addScriptToEvaluateOnNewDocument", { source: "delete Object.groupBy; delete Map.groupBy;" });
await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: "http://127.0.0.1:8080" });
await waitFor("document.readyState === 'complete'");
await evaluate("(async () => { localStorage.removeItem('lumos-reading-templates'); localStorage.removeItem('lumos-reading-settings'); await new Promise(resolve => { const request = indexedDB.deleteDatabase('lumos-fonts-v1'); request.onsuccess = request.onerror = request.onblocked = resolve; }); location.reload(); })()");
await waitFor("document.querySelector('.series-book-card')");

const cards = await evaluate("document.querySelectorAll('.book-card').length");
const seriesUsesBookLayout = await evaluate("document.querySelector('.series-book-card')?.classList.contains('book-card') && !document.querySelector('.series-card')");
const libraryGroups = await evaluate("[...document.querySelectorAll('.library-kind')].map(x => ({ text: x.textContent.trim(), icon: x.querySelector('svg')?.getAttribute('class') ?? '' }))");
const categories = await evaluate("[...document.querySelectorAll('.nav-item.category')].map(x => x.textContent.trim())");
await evaluate("[...document.querySelectorAll('.nav-item')].find(x => x.textContent.includes('最近阅读')).click()");
await waitFor("document.querySelector('.section-heading h2')?.textContent.includes('最近读过')");
const recent = await evaluate("document.querySelector('.section-heading h2').textContent");
await evaluate("[...document.querySelectorAll('.nav-item')].find(x => x.textContent.trim() === '浏览全库').click()");
await waitFor("document.querySelector('.series-book-card')");
await evaluate("[...document.querySelectorAll('.series-book-card')].find(x => x.textContent.includes('難得拿到外掛')).click()");
await waitFor("document.querySelector('.volume-picker')");
const pickerVolumes = await evaluate("document.querySelectorAll('.volume-choice').length");
const lastReadMark = await evaluate("document.querySelector('.volume-choice .last-read')?.textContent ?? ''");
const pickerLayout = await evaluate("(() => { const grid = document.querySelector('.volume-picker-grid'); const item = document.querySelector('.volume-choice'); const label = item.querySelector('strong'); return { grid: Math.round(grid.getBoundingClientRect().width), item: Math.round(item.getBoundingClientRect().width), columns: getComputedStyle(grid).gridTemplateColumns.split(' ').length, filename: label.textContent, whiteSpace: getComputedStyle(label).whiteSpace, overflow: getComputedStyle(label).textOverflow }; })()");
await new Promise((resolve) => setTimeout(resolve, 350));
const volumeScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[5] ?? "/tmp/lumosreader-volume-preview.png", Buffer.from(volumeScreenshot.data, "base64"));
await evaluate("document.querySelector('.volume-choice .last-read').closest('.volume-choice').click()");
await waitFor("document.querySelector('.comic-stage') && !document.querySelector('.reader-loading')", 15000);
const mangaRTL = await evaluate("document.querySelector('.comic-pages').classList.contains('rtl')");
const groupByCompatibility = await evaluate("typeof Object.groupBy === 'function' && typeof Map.groupBy === 'function'");
const readerFullscreen = await evaluate("({ bar: Boolean(document.querySelector('.reader-bar')), body: Math.round(document.querySelector('.reader-body').getBoundingClientRect().height), viewport: innerHeight })");
const readerExit = await evaluate("Boolean(document.querySelector('.reader > .reader-exit'))");
const pageButtons = await evaluate("({ count: document.querySelectorAll('.tap-zone > span').length, left: document.querySelector('.tap-zone.left').ariaLabel, right: document.querySelector('.tap-zone.right').ariaLabel })");
const comicPage = await evaluate("document.querySelector('.comic-pages img:not(.comic-preload)').alt");
await evaluate("document.querySelector('.tap-zone.right').click()");
await waitFor(`document.querySelector('.comic-pages img:not(.comic-preload)').alt !== ${JSON.stringify(comicPage)}`, 15000);
const comicSlide = await evaluate("document.querySelector('.comic-pages img:not(.comic-preload)').classList.contains('turn-slide')");
await evaluate("document.querySelector('.tap-zone.center').click()");
await waitFor("document.querySelector('.floating-reader-bar') && !document.querySelector('.chapter-panel')");
const readerMenu = await evaluate("({ library: Boolean(document.querySelector('[aria-label=\"返回书库\"]')), chapters: Boolean(document.querySelector('.chapter-panel')) })");
await evaluate("document.querySelector('[aria-label=\"章节与卷册\"]').click()");
await waitFor("document.querySelector('.chapter-panel')");
const chapterLayout = await evaluate("(() => { const panel = document.querySelector('.chapter-panel'); const scroll = document.querySelector('.chapter-scroll'); const before = panel.getBoundingClientRect(); scroll.scrollTop = scroll.scrollHeight; const after = panel.getBoundingClientRect(); return { height: Math.round(before.height), stable: before.top === after.top && before.height === after.height, outerOverflow: getComputedStyle(panel).overflowY, innerOverflow: getComputedStyle(scroll).overflowY, overscroll: getComputedStyle(scroll).overscrollBehavior }; })()");
const volumes = await evaluate("[...document.querySelectorAll('.chapter-panel section')].find(x => x.querySelector('small')?.textContent === '卷册')?.querySelectorAll('button').length ?? 0");
const paging = await evaluate("document.querySelector('.chapter-pagination span').textContent");
const firstVolume = await evaluate("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent");
await evaluate("document.querySelector('[aria-label=\"切换排列顺序\"]').click()");
await waitFor("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent.includes('卷02')");
const reverseVolume = await evaluate("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent");
await evaluate("(() => { const input = document.querySelector('.chapter-tools input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, '卷01'); input.dispatchEvent(new Event('input', { bubbles: true })); })()");
await waitFor("document.querySelectorAll('.chapter-list > button').length === 1");
const searchResult = await evaluate("document.querySelector('.chapter-list > button').textContent");
await evaluate("(() => { const input = document.querySelector('.chapter-tools input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, ''); input.dispatchEvent(new Event('input', { bubbles: true })); document.querySelector('[aria-label=\"切换排列顺序\"]').click(); })()");
await waitFor("document.querySelector('.chapter-pagination span').textContent.startsWith('1 /')");
await evaluate("document.querySelector('[aria-label=\"下一页目录\"]').click()");
await waitFor("document.querySelector('.chapter-pagination span').textContent.startsWith('2 /')");
await evaluate("document.querySelector('[aria-label=\"上一页目录\"]').click()");
await new Promise((resolve) => setTimeout(resolve, 350));
const screenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[2] ?? "/tmp/lumosreader-reader-preview.png", Buffer.from(screenshot.data, "base64"));
await evaluate("document.querySelector('[aria-label=\"阅读设置\"]').click()");
await waitFor("document.querySelector('.settings-panel')");

const settings = await evaluate("document.querySelector('.settings-panel').textContent");
const colorInputs = await evaluate("document.querySelectorAll('.settings-panel input[type=color]').length");
const fontControls = await evaluate("(() => { const panel = document.querySelector('.settings-panel'); const library = panel.querySelector('.font-library'); return { merged: Boolean(library) && !panel.querySelector('[aria-label=\"服务端字体\"]') && !panel.querySelector('.font-upload'), order: [...library.querySelectorAll('.font-list strong')].slice(0, 4).map(x => x.textContent), remote: false }; })()");
const settingsLayout = await evaluate("(() => { const panel = document.querySelector('.settings-panel'); const scroll = document.querySelector('.settings-scroll'); const before = panel.getBoundingClientRect(); scroll.scrollTop = scroll.scrollHeight; const after = panel.getBoundingClientRect(); return { height: Math.round(before.height), stable: before.top === after.top && before.height === after.height, outerOverflow: getComputedStyle(panel).overflowY, innerOverflow: getComputedStyle(scroll).overflowY, overscroll: getComputedStyle(scroll).overscrollBehavior, hasSystemTheme: panel.textContent.includes('界面主题') }; })()");
await showFont("Geneva.ttf", "document.querySelector('.settings-panel')");
fontControls.remote = await evaluate("[...document.querySelectorAll('.settings-panel .font-list > button.remote')].some(x => x.textContent.includes('Geneva.ttf'))");
await send("Network.emulateNetworkConditions", { offline: false, latency: 100, downloadThroughput: 4096, uploadThroughput: 4096 });
await evaluate("[...document.querySelectorAll('.font-list > button')].find(x => x.textContent.includes('Geneva.ttf')).click()");
await waitFor("document.querySelector('.font-progress')");
const fontProgress = await evaluate("Boolean(document.querySelector('.font-progress small'))");
await send("Network.emulateNetworkConditions", { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
await waitFor("[...document.querySelectorAll('.font-list > button.local.active')].some(x => x.textContent.includes('Geneva.ttf'))", 30000);
const fontDownload = await evaluate("(async () => ({ cached: await new Promise(resolve => { const open = indexedDB.open('lumos-fonts-v1'); open.onsuccess = () => { const request = open.result.transaction('fonts').objectStore('fonts').get('Geneva.ttf'); request.onsuccess = () => resolve(request.result instanceof Blob); request.onerror = () => resolve(false); }; open.onerror = () => resolve(false); }), selected: JSON.parse(localStorage.getItem('lumos-reading-settings')).fontFile }))()");
await evaluate("(() => { const select = [...document.querySelectorAll('.setting-fields label')].find(x => x.textContent.includes('页面模式')).querySelector('select'); select.value = '2'; select.dispatchEvent(new Event('change', { bubbles: true })); })()");
await waitFor("document.querySelector('foliate-view').renderer.getAttribute('max-column-count') === '2'");
await evaluate("[...document.querySelectorAll('.template-grid button')].find(x => x.textContent.includes('墨水屏')).click()");
await waitFor("getComputedStyle(document.querySelector('.reader')).getPropertyValue('--reader-bg').trim() === '#ffffff'");
await evaluate("document.querySelector('.toggle-field input').click()");
await waitFor("document.querySelectorAll('.tap-zone > span').length === 0");
await evaluate("(() => { const select = [...document.querySelectorAll('.setting-fields label')].find(x => x.textContent.includes('翻页动画')).querySelector('select'); select.value = 'fade'; select.dispatchEvent(new Event('change', { bubbles: true })); })()");
await evaluate("(() => { const input = document.querySelector('.save-template input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, '自动测试模板'); input.dispatchEvent(new Event('input', { bubbles: true })); input.closest('form').requestSubmit(); })()");
await waitFor("document.querySelector('.settings-panel').textContent.includes('自动测试模板')");
await evaluate("[...document.querySelectorAll('.font-list > button.local')].find(x => x.textContent.includes('Geneva.ttf')).click()");
await waitFor("[...document.querySelectorAll('.font-list > button.active')].some(x => x.textContent.includes('Geneva.ttf'))");

await evaluate("document.querySelector('[aria-label=\"关闭设置\"]').click(); document.querySelector('.tap-zone.center').click()");
await waitFor("document.querySelector('[aria-label=\"返回书库\"]')");
await evaluate("document.querySelector('[aria-label=\"返回书库\"]').click()");
await waitFor("document.querySelector('.series-book-card')");
await evaluate("[...document.querySelectorAll('.book-card')].find(x => x.textContent.includes('武炼巅峰')).click()");
await waitFor("document.querySelector('foliate-view') && !document.querySelector('.reader-loading')", 15000);
await waitFor("document.querySelector('foliate-view').renderer.getContents().some(content => [...content.doc.querySelectorAll('style')].some(style => style.textContent.includes('LumosCustom')))");
fontDownload.rendered = await evaluate("document.querySelector('foliate-view').renderer.getContents().some(content => [...content.doc.querySelectorAll('style')].some(style => style.textContent.includes('LumosCustom'))) ");
await evaluate("document.querySelector('.tap-zone.center').click()");
await waitFor("document.querySelector('[aria-label=\"返回书库\"]')");
await evaluate("document.querySelector('[aria-label=\"返回书库\"]').click()");
await waitFor("document.querySelector('.series-book-card')");
await evaluate("document.querySelector('.account-trigger').click()");
await waitFor("document.querySelector('.account-popover')");
const themeEntry = await evaluate("[...document.querySelectorAll('.account-popover button')].some(x => x.textContent.includes('界面主题'))");
const fontEntry = await evaluate("[...document.querySelectorAll('.account-popover button')].some(x => x.textContent.includes('字体库'))");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('字体库')).click()");
await waitFor("document.querySelector('.user-panel .font-library')");
await waitFor("document.querySelector('.user-panel .font-list > button.remote')");
await showFont("Geneva.ttf", "document.querySelector('.user-panel')");
const managerFonts = await evaluate("(() => { const panel = document.querySelector('.user-panel'); const font = [...panel.querySelectorAll('.font-list > button')].find(x => x.textContent.includes('Geneva.ttf')); return { upload: panel.querySelector('.font-upload')?.textContent.includes('上传字体到服务端'), downloaded: font?.classList.contains('local') && font?.textContent.includes('已下载至本地'), remote: false, pages: Number(panel.querySelector('.font-pagination span').textContent.split('/')[1]) }; })()");
await firstFontPage("document.querySelector('.user-panel')");
managerFonts.remote = await evaluate("Boolean(document.querySelector('.user-panel .font-list > button.remote'))");
await evaluate("document.querySelector('[aria-label=\"下一页字体\"]').click()");
await waitFor("document.querySelector('.user-panel .font-pagination span').textContent.startsWith('2 /')");
managerFonts.nextPage = true;
await evaluate("document.querySelector('.user-panel > header button').click()");
await waitFor("document.querySelector('.account-popover')");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('界面主题')).click()");
await waitFor("document.querySelector('.theme-settings')");
await evaluate("(() => { const select = document.querySelector('.theme-settings select'); select.value = 'gradient'; select.dispatchEvent(new Event('change', { bubbles: true })); })()");
await waitFor("document.documentElement.dataset.appTheme === 'gradient'");
const systemTheme = await evaluate("document.querySelector('.user-panel').textContent.includes('微光阅外观') && document.documentElement.dataset.appTheme === 'gradient'");
await evaluate("document.querySelector('.user-panel > header button').click()");
await waitFor("document.querySelector('.account-popover')");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('阅读数据')).click()");
await waitFor("document.querySelector('.stats-panel')");
const stats = await evaluate("document.querySelector('.stats-panel').textContent");
await evaluate("document.querySelector('.user-panel > header button').click()");
await waitFor("document.querySelector('.account-popover')");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('书架设置')).click()");
await waitFor("document.querySelector('.directory-status')");
const liveDirectories = await evaluate("document.querySelector('.directory-status').textContent.includes('已发现') && document.querySelector('.directory-status button').textContent.includes('刷新目录')");
const shelfKinds = await evaluate("[...document.querySelectorAll('.shelf-setting select')].map(x => [...x.options].map(o => o.textContent))");
const addShelf = await evaluate("document.querySelector('.add-shelf').textContent.includes('添加书架')");
const directoryTree = { label: "", text: "" };
await new Promise((resolve) => setTimeout(resolve, 350));
const shelvesScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[6] ?? "/tmp/lumosreader-shelves-preview.png", Buffer.from(shelvesScreenshot.data, "base64"));
await evaluate("document.querySelector('.user-panel > header button').click()");
await new Promise((resolve) => setTimeout(resolve, 350));
const libraryScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[3] ?? "/tmp/lumosreader-library-preview.png", Buffer.from(libraryScreenshot.data, "base64"));

await send("Emulation.setDeviceMetricsOverride", { width: 758, height: 1024, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: "http://127.0.0.1:8080" });
await waitFor("document.querySelector('.series-book-card')");
await new Promise((resolve) => setTimeout(resolve, 350));
const mobileManager = await evaluate("(() => { const trigger = document.querySelector('.mobile-account .account-trigger'); return Boolean(trigger && trigger.getBoundingClientRect().width); })()");
const mobileScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[4] ?? "/tmp/lumosreader-mobile-preview.png", Buffer.from(mobileScreenshot.data, "base64"));

if (!seriesUsesBookLayout || libraryGroups.length < 2 || libraryGroups[0].icon === libraryGroups[1].icon || categories.some((name) => name.includes("難得拿到外掛")) || pickerVolumes !== 2 || pickerLayout.columns !== 1 || pickerLayout.item < pickerLayout.grid - 10 || !pickerLayout.filename.includes("卷01") || pickerLayout.filename.includes(".epub") || pickerLayout.whiteSpace === "nowrap" || pickerLayout.overflow === "ellipsis" || !lastReadMark.includes("上次读到") || readerFullscreen.bar || readerFullscreen.body !== readerFullscreen.viewport || !readerExit || !readerMenu.library || readerMenu.chapters || pageButtons.count !== 2 || pageButtons.left !== "上一页" || pageButtons.right !== "下一页" || !comicSlide || volumes !== 2 || paging === "1 / 1" || !firstVolume.includes("卷01") || firstVolume.includes(".epub") || !reverseVolume.includes("卷02") || reverseVolume.includes(".epub") || !searchResult.includes("卷01") || !chapterLayout.stable || chapterLayout.outerOverflow !== "hidden" || chapterLayout.innerOverflow !== "auto" || chapterLayout.overscroll !== "none" || !settingsLayout.stable || settingsLayout.outerOverflow !== "hidden" || settingsLayout.innerOverflow !== "auto" || settingsLayout.overscroll !== "none" || settingsLayout.hasSystemTheme || colorInputs !== 2 || !fontControls.merged || fontControls.order.join() !== "书籍原字体,系统字体,宋体 / 衬线,黑体 / 无衬线" || !fontControls.remote || !fontProgress || !fontDownload.cached || fontDownload.selected !== "Geneva.ttf" || !fontDownload.rendered || !fontDownloads.some((download) => download.url.endsWith('/api/fonts/Geneva.ttf') && download.status === 200) || !fontEntry || !managerFonts.upload || !managerFonts.downloaded || !managerFonts.remote || managerFonts.pages < 2 || !managerFonts.nextPage || !themeEntry || !systemTheme || !mobileManager || !liveDirectories || !addShelf || shelfKinds.some((options) => !options.includes("图书") || !options.includes("漫画")) || !settings.includes("漫画优化") || !settings.includes("翻页动画") || !settings.includes("显示半透明翻页键") || !stats.includes("累计阅读") || !ranges.some((range) => range.cacheControl.includes("no-store")) || !mangaRTL || !groupByCompatibility || runtimeExceptions.length) {
  throw new Error(`UI check failed: series=${seriesUsesBookLayout}, groups=${JSON.stringify(libraryGroups)}, picker=${pickerVolumes}/${lastReadMark}/${JSON.stringify(pickerLayout)}, reader=${JSON.stringify(readerFullscreen)}/${readerExit}/${JSON.stringify(readerMenu)}, panels=${JSON.stringify(chapterLayout)}/${JSON.stringify(settingsLayout)}, fonts=${JSON.stringify(fontControls)}/${fontProgress}/${JSON.stringify(fontDownload)}/${JSON.stringify(managerFonts)}, theme=${themeEntry}/${systemTheme}/${mobileManager}, categories=${categories}, buttons=${JSON.stringify(pageButtons)}, slide=${comicSlide}, volumes=${volumes}, paging=${paging}, order=${firstVolume}/${reverseVolume}, search=${searchResult}, colors=${colorInputs}, directories=${liveDirectories}/${JSON.stringify(directoryTree)}, kinds=${JSON.stringify(shelfKinds)}, settings=${settings.length}, stats=${stats.length}, ranges=${JSON.stringify(ranges)}, mangaRTL=${mangaRTL}, groupBy=${groupByCompatibility}`);
}
console.log(JSON.stringify({ cards, recent, seriesUsesBookLayout, libraryGroups, pickerVolumes, pickerLayout, lastReadMark, reader: { fullscreen: readerFullscreen, exit: readerExit, menu: readerMenu }, panels: { chapter: chapterLayout, settings: settingsLayout }, fonts: { controls: fontControls, progress: fontProgress, download: fontDownload, manager: managerFonts }, systemTheme, mobileManager, categories, pageButtons, comicSlide, volumes, paging, order: [firstVolume, reverseVolume], searchResult, colorInputs, liveDirectories, shelfKinds, directoryTree: true, rangeRequests: ranges.length, noStore: true, customTemplate: true, mangaRTL, groupByCompatibility, runtimeExceptions, stats: true }));
socket.close();
