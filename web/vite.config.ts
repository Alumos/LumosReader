import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const foliateCompatibility = {
  name: "foliate-compatibility",
  transform(code: string, id: string) {
    const paginator = id.endsWith("/foliate-js/paginator.js");
    const fixedLayout = id.endsWith("/foliate-js/fixed-layout.js");
    if (!paginator && !fixedLayout) return;
    const patches = paginator ? [
      [".replace(/(?<=[{\\s;])-epub-/gi, '')", ".replace(/([{\\s;])-epub-/gi, '$1')"],
      ["return new Promise(resolve => {\n            this.#iframe.addEventListener('load', () => {\n                const doc = this.document", "const html = /; wv\\)/.test(navigator.userAgent) && src.startsWith('blob:') ? await fetch(src).then(response => response.text()) : null\n        return new Promise((resolve, reject) => {\n            this.#iframe.addEventListener('load', () => { try {\n                const doc = this.document"],
      ["                resolve()\n            }, { once: true })\n            this.#iframe.src = src", "                resolve()\n            } catch (error) { reject(error) }\n            }, { once: true })\n            this.#iframe.addEventListener('error', () => reject(new Error('Failed to load book section')), { once: true })\n            if (html == null) this.#iframe.src = src\n            else {\n                this.#iframe.setAttribute('sandbox', 'allow-same-origin')\n                this.#iframe.srcdoc = html\n            }"],
      [".catch(e => {\n                    console.warn(e)\n                    console.warn(new Error(`Failed to load section ${index}`))\n                    return {}\n                })", ".catch(e => { throw new Error(`Failed to load section ${index}`, { cause: e }) })"],
    ] : [
      ["return new Promise(resolve => {\n            iframe.addEventListener('load', () => {\n                const doc = iframe.contentDocument", "const html = /; wv\\)/.test(navigator.userAgent) && src.startsWith('blob:') ? await fetch(src).then(response => response.text()) : null\n        return new Promise((resolve, reject) => {\n            iframe.addEventListener('load', () => { try {\n                const doc = iframe.contentDocument"],
      ["                    onZoom,\n                })\n            }, { once: true })\n            iframe.src = src", "                    onZoom,\n                })\n            } catch (error) { reject(error) }\n            }, { once: true })\n            iframe.addEventListener('error', () => reject(new Error('Failed to load fixed-layout section')), { once: true })\n            if (html == null) iframe.src = src\n            else {\n                iframe.setAttribute('sandbox', 'allow-same-origin')\n                iframe.srcdoc = html\n            }"],
    ];
    for (const [from, to] of patches) {
      if (!code.includes(from)) throw new Error("foliate-js WebKit compatibility patch is stale");
      code = code.replace(from, to);
    }
    return code;
  },
};

export default defineConfig({
  plugins: [react(), foliateCompatibility],
  build: { target: ["chrome80", "safari15.4"] },
  server: {
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
});
