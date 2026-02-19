import { defineConfig } from "tsup";
import { copyFileSync, mkdirSync, existsSync, readdirSync, chmodSync } from "fs";
import { join } from "path";

export default defineConfig({
  entry: {
    bin: "bin.ts",
  },
  format: ["esm"],
  outDir: "dist",
  dts: false,
  clean: true,
  treeshake: true,
  minify: false,
  splitting: false,
  sourcemap: true,
  target: "node18",
  platform: "node",
  onSuccess: async () => {
    // Copy rules directory to dist
    const srcRulesDir = "src/utils/rules";
    const destRulesDir = "dist/rules";

    if (existsSync(srcRulesDir)) {
      mkdirSync(destRulesDir, { recursive: true });
      for (const file of readdirSync(srcRulesDir)) {
        copyFileSync(join(srcRulesDir, file), join(destRulesDir, file));
      }
      console.log("Copied rules directory to dist/rules");
    }

    // Make bin.js executable
    chmodSync("dist/bin.js", 0o755);
  },
});
