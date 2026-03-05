import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: {
      index: "src/opik/index.ts",
      cli: "src/opik/cli/index.ts",
      "cli-bin": "src/opik/cli/bin.ts",
    },
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
    clean: true,
    treeshake: true,
    minify: true,
  },
]);
