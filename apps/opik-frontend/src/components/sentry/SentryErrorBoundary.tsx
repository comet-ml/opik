import * as Sentry from "@sentry/react";
import React from "react";
import SentryErrorFallback from "@/components/sentry/SentryErrorFallback";
import { SENTRY_ENABLED } from "@/constants/sentry";

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
