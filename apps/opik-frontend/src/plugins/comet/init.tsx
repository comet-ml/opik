/// <reference types="@types/segment-analytics" />

import { initAnalytics } from "./analytics";
import { loadScript } from "@/plugins/comet/utils";

type EnvironmentVariablesOverwrite = {
  OPIK_SEGMENT_ID?: string;
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
  },
);
