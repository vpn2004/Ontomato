import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "node",
    // Let happy-dom supply per-window storage even when the Node runtime provides it.
    poolOptions: { threads: { execArgv: ["--no-experimental-webstorage"] } },
    // Only component tests run in the DOM.
    environmentMatchGlobs: [["**/*.component.test.ts", "happy-dom"]],
    include: ["src/**/__tests__/**/*.test.ts"],
  },
});
