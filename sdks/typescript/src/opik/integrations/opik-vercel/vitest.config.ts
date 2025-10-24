import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    environment: "node",
    include: ["src/**/*.test.ts", "tests/**/*.test.ts"],
    env: {
      OPIK_API_KEY: "test-api-key",
      OPIK_WORKSPACE: "test-workspace",
    },
  },
});
