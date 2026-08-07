import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";
import { foliateCompatibility } from "./build/foliateCompatibility.ts";

export default defineConfig({
  plugins: [react(), foliateCompatibility()],
  build: { target: ["chrome80", "safari15.4"] },
  server: {
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
});
