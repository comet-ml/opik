/// <reference types="@types/segment-analytics" />

import { initAnalytics } from "./analytics";
import { loadScript } from "@/plugins/comet/utils";
import { initNewRelic } from "@/plugins/comet/newrelic";

type EnvironmentVariablesOverwrite = {
  OPIK_SEGMENT_ID?: string;
  OPIK_NEW_RELIC_LICENSE_KEY: string;
  OPIK_NEW_RELIC_APP_ID: string;
};

declare global {
  interface Window {
    analytics: SegmentAnalytics.AnalyticsJS;
    environmentVariablesOverwrite?: EnvironmentVariablesOverwrite;
  }
}

loadScript(location.origin + `/config.js?version=${new Date().getTime()}`).then(
  () => {
    initAnalytics(window.environmentVariablesOverwrite?.OPIK_SEGMENT_ID);
    initNewRelic(
      window.environmentVariablesOverwrite?.OPIK_NEW_RELIC_LICENSE_KEY,
      window.environmentVariablesOverwrite?.OPIK_NEW_RELIC_APP_ID,
    );
  },
);
