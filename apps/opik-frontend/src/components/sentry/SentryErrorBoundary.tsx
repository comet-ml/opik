import * as Sentry from "@sentry/react";
import React from "react";
import SentryErrorFallback from "@/components/sentry/SentryErrorFallback";

const BrokenComponent = () => {
  React.useEffect(() => {
    throw new Error("Component sentry"); // Now it happens within the React tree
  }, []);

  return <div>Will never render</div>;
};

const SentryErrorBoundary = ({ children }: { children: React.ReactNode }) => {
  return (
    <Sentry.ErrorBoundary fallback={SentryErrorFallback}>
      <BrokenComponent />
      {children}
    </Sentry.ErrorBoundary>
  );
};

export default SentryErrorBoundary;
