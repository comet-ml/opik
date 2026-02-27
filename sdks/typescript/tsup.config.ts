import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: {
      index: "src/opik/index.ts",
      tui: "src/opik/tui/index.ts",
      "tui-cli": "src/opik/tui/bin.ts",
    },
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
    clean: true,
    treeshake: true,
    minify: true,
  },
]);
