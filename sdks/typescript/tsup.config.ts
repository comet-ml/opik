import { defineConfig } from "tsup";
import { version } from "./package.json";

export default defineConfig([
  {
    noExternal: ["url-join"],
    entry: {
      index: "src/opik/index.ts",
    },
    format: ["cjs", "esm"],
    outDir: "dist",
    dts: true,
    // tsup runs the configs in this array concurrently, so an unqualified clean
    // here races the CLI bundle below into the same directory.
    clean: ["!cli.js"],
    treeshake: true,
    minify: true,
  },
  {
    entry: {
      cli: "src/opik/cli/bin.ts",
    },
    format: ["esm"],
    outDir: "dist",
    banner: { js: "#!/usr/bin/env node" },
    // So a reported event says which released version produced it.
    define: { __OPIK_SDK_VERSION__: JSON.stringify(version) },
    treeshake: true,
    minify: true,
  },
]);
