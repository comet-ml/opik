import React, { useEffect } from "react";
import { AnalyticsBrowser } from "@segment/analytics-next";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import { useLocation } from "@docusaurus/router";

// Initialize analytics once outside the component
let analytics: AnalyticsBrowser | null = null;

export default function Root({ children }) {
  const { siteConfig } = useDocusaurusContext();
  const { segmentWriteKey } = siteConfig.customFields as { segmentWriteKey: string };
  const location = useLocation();

  // Initialize analytics only once if not already initialized
  if (!analytics) {
    analytics = AnalyticsBrowser.load({ writeKey: segmentWriteKey });
  }

  useEffect(() => {
    // Track page view whenever location changes
    analytics?.page({
      path: location.pathname,
      url: window.location.href,
      title: document.title,
    });
  }, [location]);

  return <>{children}</>;
}
