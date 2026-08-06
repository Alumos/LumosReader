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
socket.addEventListener("message", ({ data }) => {
  const message = JSON.parse(data);
  if (message.id) {
    const task = pending.get(message.id);
    pending.delete(message.id);
    if (message.error) task.reject(new Error(`${task.method} ${task.expression ?? ""}: ${message.error.message}`));
    else task.resolve(message.result);
  } else if (message.method === "Network.responseReceived" && message.params.response.status === 206) {
    ranges.push({
      contentRange: message.params.response.headers["Content-Range"] ?? message.params.response.headers["content-range"],
      cacheControl: message.params.response.headers["Cache-Control"] ?? message.params.response.headers["cache-control"] ?? "",
    });
  }
});

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const requestID = ++id;
  pending.set(requestID, { resolve, reject, method, expression: params.expression });
  socket.send(JSON.stringify({ id: requestID, method, params }));
});
const evaluate = async (expression) => {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
  return result.result.value;
};
const waitFor = async (expression, timeout = 10000) => {
  const started = Date.now();
  while (!(await evaluate(`Boolean(${expression})`))) {
    if (Date.now() - started > timeout) throw new Error(`Timed out: ${expression}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};

await send("Page.enable");
await send("Runtime.enable");
await send("Network.enable");
await send("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
await send("Page.navigate", { url: "http://127.0.0.1:8080" });
await waitFor("document.readyState === 'complete'");
await evaluate("localStorage.removeItem('lumos-reading-templates'); localStorage.removeItem('lumos-reading-settings'); location.reload()");
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
await evaluate("document.querySelector('.series-book-card').click()");
await waitFor("document.querySelector('.volume-picker')");
const pickerVolumes = await evaluate("document.querySelectorAll('.volume-choice').length");
const lastReadMark = await evaluate("document.querySelector('.volume-choice .last-read')?.textContent ?? ''");
const pickerLayout = await evaluate("(() => { const grid = document.querySelector('.volume-picker-grid'); const item = document.querySelector('.volume-choice'); const label = item.querySelector('strong'); return { grid: Math.round(grid.getBoundingClientRect().width), item: Math.round(item.getBoundingClientRect().width), columns: getComputedStyle(grid).gridTemplateColumns.split(' ').length, filename: label.textContent, whiteSpace: getComputedStyle(label).whiteSpace, overflow: getComputedStyle(label).textOverflow }; })()");
await new Promise((resolve) => setTimeout(resolve, 350));
const volumeScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[5] ?? "/tmp/lumosreader-volume-preview.png", Buffer.from(volumeScreenshot.data, "base64"));
await evaluate("document.querySelector('.volume-choice .last-read').closest('.volume-choice').click()");
await waitFor("document.querySelector('foliate-view') && !document.querySelector('.reader-loading')", 15000);
const mangaRTL = await evaluate("document.querySelector('foliate-view').renderer.rtl === true");
const readerBarInitial = await evaluate("Math.round(document.querySelector('.reader-bar').getBoundingClientRect().height)");
const pageButtons = await evaluate("({ count: document.querySelectorAll('.tap-zone > span').length, left: document.querySelector('.tap-zone.left').ariaLabel, right: document.querySelector('.tap-zone.right').ariaLabel })");
await evaluate("document.querySelector('.tap-zone.right').click()");
await waitFor("document.querySelector('foliate-view').getAnimations().length > 0", 15000);
const comicSlide = await evaluate("document.querySelector('foliate-view').getAnimations().length > 0");
await new Promise((resolve) => setTimeout(resolve, 300));
await evaluate("document.querySelector('.tap-zone.right').click()");
await waitFor("document.querySelector('.reader.immersive') && document.querySelector('.reader-bar').getBoundingClientRect().height === 0");
const immersive = await evaluate("({ bar: Math.round(document.querySelector('.reader-bar').getBoundingClientRect().height), body: Math.round(document.querySelector('.reader-body').getBoundingClientRect().height), viewport: innerHeight })");
const immersiveScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[7] ?? "/tmp/lumosreader-immersive-preview.png", Buffer.from(immersiveScreenshot.data, "base64"));
await evaluate("document.querySelector('.tap-zone.center').click()");
await waitFor("document.querySelector('.floating-reader-bar') && document.querySelector('.chapter-panel') && !document.querySelector('.reader.immersive') && document.querySelector('.reader-bar').getBoundingClientRect().height >= 60");
const readerBarExpanded = await evaluate("Math.round(document.querySelector('.reader-bar').getBoundingClientRect().height)");
const volumes = await evaluate("[...document.querySelectorAll('.chapter-panel section')].find(x => x.querySelector('small')?.textContent === '卷册')?.querySelectorAll('button').length ?? 0");
const paging = await evaluate("document.querySelector('.chapter-pagination span').textContent");
const firstVolume = await evaluate("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent");
await evaluate("document.querySelector('.chapter-tools button').click()");
await waitFor("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent.includes('卷02')");
const reverseVolume = await evaluate("[...document.querySelectorAll('.chapter-list')].find(x => x.querySelector('small')?.textContent === '卷册').querySelector('button').textContent");
await evaluate("(() => { const input = document.querySelector('.chapter-tools input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, '卷01'); input.dispatchEvent(new Event('input', { bubbles: true })); })()");
await waitFor("document.querySelectorAll('.chapter-list > button').length === 1");
const searchResult = await evaluate("document.querySelector('.chapter-list > button').textContent");
await evaluate("(() => { const input = document.querySelector('.chapter-tools input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, ''); input.dispatchEvent(new Event('input', { bubbles: true })); document.querySelector('.chapter-tools button').click(); })()");
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
const fontControls = await evaluate("Boolean(document.querySelector('[aria-label=\"服务端字体\"]') && [...document.querySelectorAll('.font-upload')].some(x => x.textContent.includes('上传字体到服务端')))");
await evaluate("(() => { const select = [...document.querySelectorAll('.setting-fields label')].find(x => x.textContent.includes('页面模式')).querySelector('select'); select.value = '2'; select.dispatchEvent(new Event('change', { bubbles: true })); })()");
await waitFor("document.querySelector('foliate-view').renderer.getAttribute('max-column-count') === '2'");
await evaluate("[...document.querySelectorAll('.template-grid button')].find(x => x.textContent.includes('墨水屏')).click()");
await waitFor("getComputedStyle(document.querySelector('.reader')).getPropertyValue('--reader-bg').trim() === '#ffffff'");
await evaluate("document.querySelector('.toggle-field input').click()");
await waitFor("document.querySelectorAll('.tap-zone > span').length === 0");
await evaluate("(() => { const select = [...document.querySelectorAll('.setting-fields label')].find(x => x.textContent.includes('翻页动画')).querySelector('select'); select.value = 'fade'; select.dispatchEvent(new Event('change', { bubbles: true })); })()");
await evaluate("(() => { const input = document.querySelector('.save-template input'); const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(input, '自动测试模板'); input.dispatchEvent(new Event('input', { bubbles: true })); input.closest('form').requestSubmit(); })()");
await waitFor("document.querySelector('.settings-panel').textContent.includes('自动测试模板')");

await evaluate("document.querySelector('.reader-bar > button').click()");
await waitFor("document.querySelector('.series-book-card')");
await evaluate("document.querySelector('.account-trigger').click()");
await waitFor("document.querySelector('.account-popover')");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('阅读数据')).click()");
await waitFor("document.querySelector('.stats-panel')");
const stats = await evaluate("document.querySelector('.stats-panel').textContent");
await evaluate("document.querySelector('.user-panel > header button').click()");
await waitFor("document.querySelector('.account-popover')");
await evaluate("[...document.querySelectorAll('.account-popover button')].find(x => x.textContent.includes('书架设置')).click()");
await waitFor("document.querySelector('.directory-status') && document.querySelector('.shelf-setting')");
const liveDirectories = await evaluate("document.querySelector('.directory-status').textContent.includes('已发现') && document.querySelector('.directory-status button').textContent.includes('刷新目录')");
const shelfKinds = await evaluate("[...document.querySelectorAll('.shelf-setting select')].map(x => [...x.options].map(o => o.textContent))");
await evaluate("document.querySelector('.directory-field button').click()");
await waitFor("document.querySelector('.directory-tree')");
await evaluate("[...document.querySelectorAll('.directory-toggle')].find(x => x.getAttribute('aria-label')?.includes('冒險'))?.click()");
await waitFor("document.querySelector('.directory-tree').textContent.includes('難得拿到外掛')");
const directoryTree = await evaluate("({ label: document.querySelector('.directory-tree').getAttribute('aria-label'), text: document.querySelector('.directory-tree').textContent })");
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
const mobileScreenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
await writeFile(process.argv[4] ?? "/tmp/lumosreader-mobile-preview.png", Buffer.from(mobileScreenshot.data, "base64"));

if (!seriesUsesBookLayout || libraryGroups.length !== 2 || libraryGroups[0].icon === libraryGroups[1].icon || categories.some((name) => name.includes("難得拿到外掛")) || pickerVolumes !== 2 || pickerLayout.columns !== 1 || pickerLayout.item < pickerLayout.grid - 10 || !pickerLayout.filename.includes("卷01") || pickerLayout.filename.includes(".epub") || pickerLayout.whiteSpace === "nowrap" || pickerLayout.overflow === "ellipsis" || !lastReadMark.includes("上次读到") || readerBarInitial < 60 || immersive.bar !== 0 || immersive.body !== immersive.viewport || readerBarExpanded < 60 || pageButtons.count !== 2 || pageButtons.left !== "上一页" || pageButtons.right !== "下一页" || !comicSlide || volumes !== 2 || paging === "1 / 1" || !firstVolume.includes("卷01") || firstVolume.includes(".epub") || !reverseVolume.includes("卷02") || reverseVolume.includes(".epub") || !searchResult.includes("卷01") || colorInputs !== 2 || !fontControls || !liveDirectories || shelfKinds.length < 2 || shelfKinds.some((options) => !options.includes("小说") || !options.includes("漫画")) || directoryTree.label !== "实时书库目录" || !directoryTree.text.includes("難得拿到外掛") || !settings.includes("漫画优化") || !settings.includes("翻页动画") || !settings.includes("显示半透明翻页键") || !stats.includes("累计阅读") || !ranges.some((range) => range.cacheControl.includes("no-store")) || !mangaRTL) {
  throw new Error(`UI check failed: series=${seriesUsesBookLayout}, groups=${JSON.stringify(libraryGroups)}, picker=${pickerVolumes}/${lastReadMark}/${JSON.stringify(pickerLayout)}, immersive=${readerBarInitial}/${JSON.stringify(immersive)}/${readerBarExpanded}, categories=${categories}, buttons=${JSON.stringify(pageButtons)}, slide=${comicSlide}, volumes=${volumes}, paging=${paging}, order=${firstVolume}/${reverseVolume}, search=${searchResult}, colors=${colorInputs}, fonts=${fontControls}, directories=${liveDirectories}/${JSON.stringify(directoryTree)}, kinds=${JSON.stringify(shelfKinds)}, settings=${settings.length}, stats=${stats.length}, ranges=${JSON.stringify(ranges)}, mangaRTL=${mangaRTL}`);
}
console.log(JSON.stringify({ cards, recent, seriesUsesBookLayout, libraryGroups, pickerVolumes, pickerLayout, lastReadMark, immersive: { initial: readerBarInitial, hidden: immersive, expanded: readerBarExpanded }, categories, pageButtons, comicSlide, volumes, paging, order: [firstVolume, reverseVolume], searchResult, colorInputs, fontControls, liveDirectories, shelfKinds, directoryTree: true, rangeRequests: ranges.length, noStore: true, customTemplate: true, mangaRTL, stats: true }));
socket.close();
