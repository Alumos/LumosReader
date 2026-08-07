import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const foliateWebKit = {
  name: "foliate-webkit",
  transform(code: string, id: string) {
    const paginator = id.endsWith("/foliate-js/paginator.js");
    const fixedLayout = id.endsWith("/foliate-js/fixed-layout.js");
    if (!paginator && !fixedLayout) return;
    const patches = paginator ? [
      [".replace(/(?<=[{\\s;])-epub-/gi, '')", ".replace(/([{\\s;])-epub-/gi, '$1')"],
      ["expand() {\n        const { documentElement } = this.document", "expand() {\n        if (!this.document) return\n        const { documentElement } = this.document"],
      ["return new Promise(resolve => {\n            this.#iframe.addEventListener('load', () => {\n                const doc = this.document", "return new Promise((resolve, reject) => {\n            this.#iframe.addEventListener('load', () => { try {\n                const doc = this.document"],
      ["                resolve()\n            }, { once: true })\n            this.#iframe.src = src", "                resolve()\n            } catch (error) { reject(error) }\n            }, { once: true })\n            this.#iframe.addEventListener('error', () => reject(new Error('Failed to load book section')), { once: true })\n            this.#iframe.src = src"],
      ["globalThis.visualViewport.scale > 1", "(globalThis.visualViewport?.scale ?? 1) > 1"],
      ["globalThis.visualViewport.scale === 1", "(globalThis.visualViewport?.scale ?? 1) === 1"],
      ["this.#mediaQuery.addEventListener('change', this.#mediaQueryListener)", "this.#mediaQuery.addEventListener ? this.#mediaQuery.addEventListener('change', this.#mediaQueryListener) : this.#mediaQuery.addListener(this.#mediaQueryListener)"],
      ["this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener)", "this.#mediaQuery.removeEventListener ? this.#mediaQuery.removeEventListener('change', this.#mediaQueryListener) : this.#mediaQuery.removeListener(this.#mediaQueryListener)"],
      ["this.#view.destroy()\n        this.#view = null", "this.#view?.destroy()\n        this.#view = null"],
    ] : [
      ["return new Promise(resolve => {\n            iframe.addEventListener('load', () => {\n                const doc = iframe.contentDocument", "return new Promise((resolve, reject) => {\n            iframe.addEventListener('load', () => { try {\n                const doc = iframe.contentDocument"],
      ["                    onZoom,\n                })\n            }, { once: true })\n            iframe.src = src", "                    onZoom,\n                })\n            } catch (error) { reject(error) }\n            }, { once: true })\n            iframe.addEventListener('error', () => reject(new Error('Failed to load fixed-layout section')), { once: true })\n            iframe.src = src"],
    ];
    for (const [from, to] of patches) {
      if (!code.includes(from)) throw new Error("foliate-js WebKit compatibility patch is stale");
      code = code.replace(from, to);
    }
    return code;
  },
};

export default defineConfig({
  plugins: [react(), foliateWebKit],
  build: { target: ["chrome80", "safari15.4"] },
  server: {
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
});
