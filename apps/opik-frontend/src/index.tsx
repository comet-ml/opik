import { createRoot } from "react-dom/client";
import * as Sentry from "@sentry/react";

import "tailwindcss/tailwind.css";

import App from "@/components/App";
import usePluginsStore from "@/store/PluginsStore";
import { APP_VERSION } from "@/constants/app";

import "./main.scss";
import { IS_SENTRY_ENABLED, SENTRY_DSN, SENTRY_MODE } from "@/config";

// other styles
import "react18-json-view/src/style.css";
import "react18-json-view/src/dark.css";

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

usePluginsStore.getState().setupPlugins(import.meta.env.MODE);
root.render(<App />);
