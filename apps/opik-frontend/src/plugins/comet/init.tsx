/// <reference types="@types/segment-analytics" />

import { initAnalytics } from "./analytics";

type EnvironmentVariablesOverwrite = {
  OPIK_SEGMENT_ID?: string;
};

declare global {
  interface Window {
    analytics: SegmentAnalytics.AnalyticsJS;
    environmentVariablesOverwrite: EnvironmentVariablesOverwrite;
  }
}

initAnalytics(window.environmentVariablesOverwrite.OPIK_SEGMENT_ID);
