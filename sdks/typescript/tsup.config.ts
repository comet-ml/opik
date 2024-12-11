import { defineConfig } from "tsup";

export default defineConfig([
  {
    entry: ["src/opik/index.ts"],
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
  },
]);
