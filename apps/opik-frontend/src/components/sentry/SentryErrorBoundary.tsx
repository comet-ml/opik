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
      className="rounded-md bg-red-500 p-2 text-white"
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
