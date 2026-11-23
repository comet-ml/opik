import { defineConfig } from "tsup";

export default defineConfig([
  {
    entry: {
      ViewHost: "src/ViewHost.tsx",
      HtmlFrame: "src/HtmlFrame.tsx",
      contract: "src/contract.ts",
      tokens: "src/tokens.ts",
      view: "src/view.ts",
    },
    format: ["esm"],
    target: "es2020",
    dts: false,
    clean: true,
    splitting: true,
    treeshake: true,
    external: ["react", "react/jsx-runtime", "react-dom"],
  },
  {
    entry: { vega: "libraries/vega.ts" },
    outDir: "dist/libraries",
    format: ["iife"],
    target: "es2020",
    platform: "browser",
    dts: false,
    minify: true,
    metafile: true,
    noExternal: [/.*/],
    outExtension: () => ({ js: ".js" }),
  },
]);
