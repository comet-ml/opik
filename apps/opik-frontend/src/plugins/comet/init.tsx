/// <reference types="@types/segment-analytics" />

import { initGTM } from "@/plugins/comet/analytics/gtm";
import { initReo } from "@/plugins/comet/analytics/reo";
import { initNewRelic } from "@/plugins/comet/newrelic";
import { initPosthog } from "@/plugins/comet/posthog";
import { loadScript } from "@/plugins/comet/utils";
import { initAnalytics } from "./analytics";

type EnvironmentVariablesOverwrite = {
  OPIK_SEGMENT_ID?: string;
  OPIK_ANALYTICS_ID?: string;
  OPIK_REO_CLIENT_ID?: string;
  OPIK_NEW_RELIC_LICENSE_KEY: string;
  OPIK_NEW_RELIC_APP_ID: string;
  OPIK_POSTHOG_KEY: string;
  OPIK_POSTHOG_HOST: string;
  PRODUCTION: boolean;
  ON_PREMISE: boolean;
};

declare global {
  interface Window {
    analytics: SegmentAnalytics.AnalyticsJS;
    dataLayer: Array<Record<string, unknown>>;
    environmentVariablesOverwrite?: EnvironmentVariablesOverwrite;
  }
}

loadScript(location.origin + `/config.js?version=${new Date().getTime()}`).then(
  () => {
    initPosthog(
      window.environmentVariablesOverwrite?.OPIK_POSTHOG_KEY,
      window.environmentVariablesOverwrite?.OPIK_POSTHOG_HOST,
    );
    initAnalytics(window.environmentVariablesOverwrite?.OPIK_SEGMENT_ID);
    initGTM(window.environmentVariablesOverwrite?.OPIK_ANALYTICS_ID);
    initReo(window.environmentVariablesOverwrite?.OPIK_REO_CLIENT_ID);
    initNewRelic(
      window.environmentVariablesOverwrite?.OPIK_NEW_RELIC_LICENSE_KEY,
      window.environmentVariablesOverwrite?.OPIK_NEW_RELIC_APP_ID,
    );
  },
);
