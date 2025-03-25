import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: {
      index: "src/opik/index.ts",
      "vercel/index": "src/opik/integrations/vercel/index.ts",
    },
    format: ["cjs", "esm"],
    platform: "neutral",
    outDir: "dist",
    dts: true,
    clean: true,
    treeshake: true,
    minify: true,
  },
]);
