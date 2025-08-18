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
      host: "0.0.0.0",
      port: 5174,
      proxy: {
        // TODO lala
        "/opik-ai": {
          target: "https://dev.comet.com",
          changeOrigin: true,
          secure: true,
          ws: true,
          configure: (proxy) => {
            proxy.on("proxyReq", (proxyReq) => {
              const existing = (proxyReq.getHeader("cookie") as string) || "";
              const extra =
                "sessionToken=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmRyaWlkdWRhciIsInBzdCI6ZmFsc2UsInJscyI6WyJ1c2VyIl0sImV4cCI6MTc1NjEzNDcyMX0.XkWOIbjpAQGF4HJyQLhHsqfr-nPZLKROn4d6OtuUhng; AWSALBCORS=vJrqSDQ6RCTpcItU49Y6jY6nmqiQnIBnZoYuWXmonyA6cRInfHAyL75oY5AJAsEGmj3nlioqzqR8QuV/TuTPc5luA8GxUJx+1ERqSAoR8ixbMAXNjS8//TXcnyb4lXflItqIHr+JoRK8HRggjUB/WakcKKWNekyDe6wATaOY8eVSGEZTlaIAhzmBzqBvwau4AiNoWloe7tJBpKnpUJb2E5e419Gct3e1b6s8CjZ8MN7I4n52WPyrEW5eEX+D57A=";
              const merged = [existing, extra].filter(Boolean).join("; ");
              proxyReq.setHeader("cookie", merged);
            });
          },
        },
      },
    },
  } satisfies UserConfig;
});
