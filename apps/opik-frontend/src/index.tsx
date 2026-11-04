import { createRoot } from "react-dom/client";
import * as Sentry from "@sentry/react";

import "tailwindcss/tailwind.css";

import React, { Suspense } from "react";
import usePluginsStore from "@/store/PluginsStore";
import Loader from "@/shared/Loader/Loader";
import { APP_VERSION } from "@/constants/app";
import { runLocalStorageMigrations } from "@/lib/ls-migrations";
import { handlePreloadError } from "@/lib/chunk-load-recovery";

import "./main.scss";
import "./styles/chart-tokens/widget-tokens.css";
import "./styles/chart-tokens/chart-skeleton.css";
import { IS_SENTRY_ENABLED, SENTRY_DSN, SENTRY_MODE } from "@/config";

// other styles
import "react18-json-view/src/style.css";
import "react18-json-view/src/dark.css";

window.addEventListener("vite:preloadError", handlePreloadError);

const V2App = React.lazy(() => import("@/v2/App"));

const container = document.getElementById("root") as HTMLDivElement;
const root = createRoot(container);

if (IS_SENTRY_ENABLED) {
  Sentry.init({
    dsn: SENTRY_DSN,
    integrations: [Sentry.browserTracingIntegration()],
    tracesSampleRate: 1.0,
    environment: SENTRY_MODE,
    release: APP_VERSION,
  });
}

async function bootstrap() {
  await usePluginsStore.getState().setupPlugins();
  runLocalStorageMigrations();
  root.render(
    <Suspense fallback={<Loader />}>
      <V2App />
    </Suspense>,
  );
}

void bootstrap();
