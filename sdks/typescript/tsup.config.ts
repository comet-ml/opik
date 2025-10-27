import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: {
      index: "src/opik/index.ts",
      "vercel/index": "src/opik/integrations/opik-vercel/index.ts",
    },
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
    clean: true,
    treeshake: true,
    minify: true,
  },
]);
