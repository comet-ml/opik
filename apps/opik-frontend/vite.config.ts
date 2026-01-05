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
      host: "0.0.0.0", // Allow access from network (enables mobile device testing)
      port: 5174,
      // Proxy configuration for local development
      // Mirrors production nginx behavior: strips /api prefix before forwarding to backend
      // This enables:
      // - Mobile device testing on local network (e.g., http://192.168.1.100:5174)
      // - No hardcoded IP addresses needed
      // - Development environment matches production architecture
      // Example: /api/v1/projects -> http://localhost:8080/v1/projects
      proxy: {
        "/api": {
          target: "http://localhost:8080", // Backend server
          changeOrigin: true, // Handle CORS properly
          rewrite: (path) => path.replace(/^\/api/, ""), // Strip /api prefix
        },
      },
    },
  } satisfies UserConfig;
});
