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
  const opikAiBackendPort = parseInt(env.VITE_OPIK_AI_BACKEND_PORT || "8081", 10);

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
        "/api": {
          target: `http://localhost:${backendPort}`,
          changeOrigin: true,
          rewrite: (requestPath) => requestPath.replace(/^\/api/, ""),
        },
        "/opik-ai": {
          target: `http://localhost:${opikAiBackendPort}`,
          changeOrigin: true,
        },
      },
    },
  } satisfies UserConfig;
});
