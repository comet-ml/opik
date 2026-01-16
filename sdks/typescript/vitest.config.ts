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
      // Pass through OPIK_URL_OVERRIDE for local testing (only if set)
      ...(process.env.OPIK_URL_OVERRIDE && {
        OPIK_URL_OVERRIDE: process.env.OPIK_URL_OVERRIDE,
      }),
    },
  },
} satisfies ViteUserConfig;

export default config;
