import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const foliateWebKit = {
  name: "foliate-webkit",
  transform(code: string, id: string) {
    if (!id.endsWith("/foliate-js/paginator.js")) return;
    const patches = [
      [".replace(/(?<=[{\\s;])-epub-/gi, '')", ".replace(/([{\\s;])-epub-/gi, '$1')"],
      ["expand() {\n        const { documentElement } = this.document", "expand() {\n        if (!this.document) return\n        const { documentElement } = this.document"],
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
  build: { target: "safari15.4" },
  server: {
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
});
