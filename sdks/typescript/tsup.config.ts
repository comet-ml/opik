import { defineConfig } from "tsup";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: ["src/opik/index.ts"],
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
  },
]);
