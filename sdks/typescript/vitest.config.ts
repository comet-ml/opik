import { ViteUserConfig } from "vitest/config";
import tsconfigPaths from "vite-tsconfig-paths";

const config = {
  plugins: [tsconfigPaths()],
  test: {
    globals: true,
    environment: "node",
    include: ["tests/**/*.test.ts"],
  },
} satisfies ViteUserConfig;

export default config;
