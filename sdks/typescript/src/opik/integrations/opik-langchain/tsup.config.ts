import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
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
