/// <reference types="vitest" />
import { TanStackRouterVite } from "@tanstack/router-vite-plugin";
import react from "@vitejs/plugin-react-swc";
import * as path from "path";
import { defineConfig, loadEnv, UserConfig } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import svgr from "vite-plugin-svgr";

// https://vitejs.dev/config https://vitest.dev/config
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");

  // Support dynamic ports for multi-worktree development
  // VITE_DEV_PORT: Frontend dev server port (default: 5174)
  // VITE_BACKEND_PORT: Backend server port for proxy target (default: 8080)
  // VITE_OPIK_AI_BACKEND_PORT: AI backend server port for proxy target (default: 8081)
  const devPort = parseInt(env.VITE_DEV_PORT || "5174", 10);
  const backendPort = parseInt(env.VITE_BACKEND_PORT || "8080", 10);
  const opikAiBackendPort = parseInt(
    env.VITE_OPIK_AI_BACKEND_PORT || "8081",
    10,
  );
  const assistantPort = parseInt(env.VITE_ASSISTANT_SIDEBAR_PORT || "3333", 10);
  const assistantApiPort = parseInt(env.VITE_ASSISTANT_API_PORT || "9080", 10);
  // ai-cost-backend (cost-api) — serves the AI Spend API. dev-runner sets the
  // port; default matches cost-api's own default.
  const aiCostBackendPort = parseInt(
    env.VITE_AI_COST_BACKEND_PORT || "8000",
    10,
  );

  return {
    base: env.VITE_BASE_URL || "/",
    plugins: [
      svgr({
        svgrOptions: {
          icon: true,
        },
      }),
      react(),
      tsconfigPaths(),
      TanStackRouterVite({ enableRouteGeneration: false }),
    ],
    test: {
      globals: true,
      environment: "happy-dom",
      setupFiles: ".vitest/setup",
      include: ["**/*.test.{ts,tsx}"],
    },
    resolve: {
      preserveSymlinks: true,
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
    build: {
      sourcemap: true,
    },
    server: {
      host: "0.0.0.0",
      port: devPort,
      // Proxy /api/* to backend, stripping the /api prefix (mirrors production nginx)
      // Example: /api/v1/projects -> http://localhost:8080/v1/projects
      proxy: {
        // AI Spend → local cost-api. Declared before /api so this longer
        // prefix wins (mirrors the prod nginx ai-spend divert). The plugin
        // always targets cost-api; the opik-backend ai-spend endpoints are
        // being retired. /api/v1/private/ai-spend/X -> :port/cost-api/v1/private/ai-spend/X
        "/api/v1/private/ai-spend": {
          target: `http://localhost:${aiCostBackendPort}`,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/api/, "/cost-api"),
        },
        "/api": {
          target: `http://localhost:${backendPort}`,
          changeOrigin: true,
          rewrite: (requestPath) => requestPath.replace(/^\/api/, ""),
        },
        "/opik-ai": {
          target: `http://localhost:${opikAiBackendPort}`,
          changeOrigin: true,
        },
        "/assistant-api": {
          target: `http://localhost:${assistantApiPort}`,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/assistant-api/, ""),
        },
        "/assistant": {
          target: `http://localhost:${assistantPort}`,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/assistant/, ""),
        },
      },
    },
  } satisfies UserConfig;
});
