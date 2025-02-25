import * as Sentry from "@sentry/react";
import React from "react";
import SentryErrorFallback from "@/components/sentry/SentryErrorFallback";

const SentryErrorBoundary = ({ children }: { children: React.ReactNode }) => {
  return (
    <Sentry.ErrorBoundary fallback={SentryErrorFallback}>
      {children}
    </Sentry.ErrorBoundary>
  );
};

export default SentryErrorBoundary;
