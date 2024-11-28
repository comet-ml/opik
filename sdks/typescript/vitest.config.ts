import { ViteUserConfig } from "vitest/config";
import tsconfigPaths from "vite-tsconfig-paths";

const config = {
  plugins: [tsconfigPaths()],
} satisfies ViteUserConfig;

export default config;
