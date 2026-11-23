import { homedir } from "node:os";
import path from "node:path";
import {
  Browser,
  detectBrowserPlatform,
  getInstalledBrowsers,
  install,
  resolveBuildId,
} from "@puppeteer/browsers";

const cacheDir =
  process.env.PUPPETEER_CACHE_DIR ??
  path.join(homedir(), ".cache", "puppeteer");

export async function chromePath() {
  if (process.env.CHROME) return process.env.CHROME;
  const installed = (await getInstalledBrowsers({ cacheDir })).find(
    (b) => b.browser === Browser.CHROME,
  );
  if (installed) return installed.executablePath;
  try {
    const buildId = await resolveBuildId(
      Browser.CHROME,
      detectBrowserPlatform(),
      "stable",
    );
    console.error(`downloading Chrome ${buildId} into ${cacheDir}`);
    return (await install({ browser: Browser.CHROME, buildId, cacheDir }))
      .executablePath;
  } catch (error) {
    throw new Error(
      `could not download Chrome into ${cacheDir} (${error instanceof Error ? error.message : error}); set CHROME to a Chrome or Chromium executable`,
    );
  }
}
