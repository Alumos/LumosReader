const port = process.argv[2] ?? "9223";
const origin = process.argv[3] ?? "http://127.0.0.1:18080";
const targets = await fetch(`http://127.0.0.1:${port}/json/list`).then((response) => response.json());
const target = targets.find((item) => item.url.startsWith(origin));
if (!target) throw new Error(`No WebView target for ${origin}`);

const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});

let id = 0;
const pending = new Map();
const requests = [];
const exceptions = [];
socket.addEventListener("message", ({ data }) => {
  const message = JSON.parse(data);
  if (message.id) {
    pending.get(message.id)?.(message.result);
    pending.delete(message.id);
  } else if (message.method === "Network.requestWillBeSent") {
    requests.push(message.params.request.url);
  } else if (message.method === "Runtime.exceptionThrown") {
    exceptions.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
  }
});
const send = (method, params = {}) => new Promise((resolve) => {
  const requestID = ++id;
  pending.set(requestID, resolve);
  socket.send(JSON.stringify({ id: requestID, method, params }));
});
const evaluate = async (expression) => {
  const response = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description ?? response.exceptionDetails.text);
  return response.result.value;
};
const waitFor = async (expression, timeout = 25000) => {
  const start = Date.now();
  while (!await evaluate(`Boolean(${expression})`)) {
    if (Date.now() - start > timeout) throw new Error(`Timed out: ${expression}\n${await evaluate("document.body.innerText")}`);
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};
const openBook = async (title) => {
  const before = requests.length;
  const clicked = await evaluate(`(()=>{const card=[...document.querySelectorAll('.book-card')].find(item=>item.textContent.includes(${JSON.stringify(title)}));card?.click();return Boolean(card)})()`);
  if (!clicked) throw new Error(`Book not found: ${title}`);
  await waitFor("document.querySelector('foliate-view') && !document.querySelector('.reader-loading') && (document.querySelector('.tap-zone') || document.querySelector('.reader-message'))");
  const result = await evaluate(`({
    foliate: Boolean(document.querySelector('foliate-view')),
    comic: Boolean(document.querySelector('.comic-stage')),
    error: document.querySelector('.reader-message')?.innerText ?? '',
    progress: document.querySelector('.reader-location')?.textContent ?? '',
    ua: navigator.userAgent
  })`);
  result.pageRequests = requests.slice(before).filter((url) => url.includes("/pages"));
  if (title === "固定版式测试") {
    result.spreadTransition = await evaluate(`(async () => {
      const view = document.querySelector('foliate-view');
      await view.goTo({ index: 0 });
      await view.next();
      const counts = [view.renderer.getContents().length];
      const sampler = setInterval(() => counts.push(view.renderer.getContents().length), 1);
      await view.next();
      clearInterval(sampler);
      counts.push(view.renderer.getContents().length);
      return { minimumFrames: Math.min(...counts), hostAnimations: view.getAnimations().length };
    })()`);
    if (result.spreadTransition.minimumFrames < 1 || result.spreadTransition.hostAnimations) {
      throw new Error(`${title}: blank or duplicate-animation frame detected ${JSON.stringify(result.spreadTransition)}`);
    }
  }
  if (result.error) throw new Error(`${title}: ${result.error}`);
  await evaluate("document.querySelector('.reader-exit').click()");
  await waitFor("document.querySelector('.book-grid')");
  return result;
};

await send("Runtime.enable");
await send("Network.enable");
const normal = await openBook("微光阅读样章");
const fixed = await openBook("固定版式测试");
console.log(JSON.stringify({ normal, fixed, exceptions }, null, 2));
socket.close();
