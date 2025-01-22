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
  const baseApiUrl = env.VITE_BASE_API_URL || "/api";

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
      port: 5174,
      proxy: {
        "/opik/api": {
          target: "https://www.comet.com/",
          changeOrigin: true,
        },
        "/api": {
          target: "https://www.comet.com/",
          changeOrigin: true,
        },
      },
    },
  } satisfies UserConfig;
});
