import * as Sentry from "@sentry/react";
import React, { useState } from "react";
import SentryErrorFallback from "@/components/sentry/SentryErrorFallback";

const BreakButton = () => {
  const [shouldBreak, setShouldBreak] = useState(false);

  if (shouldBreak) {
    throw new Error("The UI has been intentionally broken!");
  }

  return (
    <button
      onClick={() => setShouldBreak(true)}
      className="p-2 bg-red-500 text-white rounded-md"
    >
      Break the UI
    </button>
  );
};

const SentryErrorBoundary = ({ children }: { children: React.ReactNode }) => {
  return (
    <Sentry.ErrorBoundary fallback={SentryErrorFallback}>
      <BreakButton />
      {children}
    </Sentry.ErrorBoundary>
  );
};

export default SentryErrorBoundary;
