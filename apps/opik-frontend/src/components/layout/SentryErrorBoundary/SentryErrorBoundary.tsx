import * as Sentry from "@sentry/react";
import React from "react";
import SentryErrorFallback from "@/components/layout/SentryErrorBoundary/SentryErrorFallback";
import { SENTRY_ENABLED } from "@/config";

const SentryErrorBoundary = ({ children }: { children: React.ReactNode }) => {
  if (!SENTRY_ENABLED) {
    return children;
  }

  return (
    <Sentry.ErrorBoundary fallback={SentryErrorFallback}>
      {children}
    </Sentry.ErrorBoundary>
  );
};

export default SentryErrorBoundary;
