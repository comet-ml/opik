import { defineConfig } from "tsup";

export default defineConfig([
  {
    entry: {
      index: "src/index.ts",
    },
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
    clean: true,
    treeshake: true,
    minify: true,
  },
]);
