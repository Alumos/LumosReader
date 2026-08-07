import type { Plugin } from "vite";

// foliate-js uses blob URLs for section iframes. Android WebView can leave a
// sandboxed blob iframe waiting forever; browsers such as Via also hide the
// usual `; wv)` UA token. Use Android itself as the capability signal and feed
// the already-resolved section HTML through srcdoc.
export function foliateCompatibility(): Plugin {
  return {
    name: "foliate-android-compatibility",
    transform(code, id) {
      const paginator = id.endsWith("/foliate-js/paginator.js");
      const fixedLayout = id.endsWith("/foliate-js/fixed-layout.js");
      if (!paginator && !fixedLayout) return;
      const androidBlob = "/Android/i.test(navigator.userAgent) && src.startsWith('blob:')";
      const patches = paginator ? [
        [".replace(/(?<=[{\\s;])-epub-/gi, '')", ".replace(/([{\\s;])-epub-/gi, '$1')"],
        ["return new Promise(resolve => {\n            this.#iframe.addEventListener('load', () => {\n                const doc = this.document", `const html = ${androidBlob} ? await fetch(src).then(response => response.text()) : null\n        return new Promise((resolve, reject) => {\n            this.#iframe.addEventListener('load', () => { try {\n                const doc = this.document`],
        ["                resolve()\n            }, { once: true })\n            this.#iframe.src = src", "                resolve()\n            } catch (error) { reject(error) }\n            }, { once: true })\n            this.#iframe.addEventListener('error', () => reject(new Error('Failed to load book section')), { once: true })\n            if (html == null) this.#iframe.src = src\n            else {\n                this.#iframe.setAttribute('sandbox', 'allow-same-origin')\n                this.#iframe.srcdoc = html\n            }"],
        [".catch(e => {\n                    console.warn(e)\n                    console.warn(new Error(`Failed to load section ${index}`))\n                    return {}\n                })", ".catch(e => { throw new Error(`Failed to load section ${index}`, { cause: e }) })"],
      ] : [
        // Keep the current spread painted while the next fixed-layout spread
        // loads. Upstream clears the closed shadow root first, which produces
        // a blank frame on every second portrait turn (the turns that cross a
        // two-page spread boundary).
        ["        this.#root.replaceChildren()\n        this.#left = null", "        const previous = Array.from(this.#root.children)\n        this.#left = null"],
        ["            this.#side = 'center'\n            this.#render()", "            this.#side = 'center'\n            for (const element of previous) element.remove()\n            this.#render()"],
        ["                : this.#right.blank ? 'left' : side\n            this.#render()", "                : this.#right.blank ? 'left' : side\n            for (const element of previous) element.remove()\n            this.#render()"],
        ["return new Promise(resolve => {\n            iframe.addEventListener('load', () => {\n                const doc = iframe.contentDocument", `const html = ${androidBlob} ? await fetch(src).then(response => response.text()) : null\n        return new Promise((resolve, reject) => {\n            iframe.addEventListener('load', () => { try {\n                const doc = iframe.contentDocument`],
        ["                    onZoom,\n                })\n            }, { once: true })\n            iframe.src = src", "                    onZoom,\n                })\n            } catch (error) { reject(error) }\n            }, { once: true })\n            iframe.addEventListener('error', () => reject(new Error('Failed to load fixed-layout section')), { once: true })\n            if (html == null) iframe.src = src\n            else {\n                iframe.setAttribute('sandbox', 'allow-same-origin')\n                iframe.srcdoc = html\n            }"],
      ];
      for (const [from, to] of patches) {
        if (!code.includes(from)) throw new Error(`foliate-js compatibility patch is stale: ${id}`);
        code = code.replace(from, to);
      }
      return code;
    },
  };
}
