import { ViteUserConfig } from "vitest/config";
import tsconfigPaths from "vite-tsconfig-paths";
import "dotenv/config"; // Load .env file

const config = {
  plugins: [tsconfigPaths()],
  test: {
    globals: true,
    environment: "node",
    include: ["tests/**/*.test.ts"],
    setupFiles: ["./tests/setup.ts"],
    env: {
      OPIK_API_KEY: process.env.OPIK_API_KEY || "test-api-key",
    },
  },
} satisfies ViteUserConfig;

export default config;
