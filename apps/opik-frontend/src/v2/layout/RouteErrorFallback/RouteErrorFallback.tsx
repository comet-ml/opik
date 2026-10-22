import React, { useEffect, useState } from "react";
import { ErrorComponent, ErrorComponentProps } from "@tanstack/react-router";
import Loader from "@/shared/Loader/Loader";
import { reloadWindowRemainingMs } from "@/lib/chunk-load-recovery";

// The spinner covers the moment before a stale-chunk reload replaces the page.
// If the page is still here when the reload window ends, the reload was
// cancelled, so the real error is shown instead of a spinner that never ends.
const RouteErrorFallback: React.FC<ErrorComponentProps> = (props) => {
  const [reloading, setReloading] = useState(
    () => reloadWindowRemainingMs() > 0,
  );

  useEffect(() => {
    if (!reloading) return;
    const timeout = setTimeout(
      () => setReloading(false),
      reloadWindowRemainingMs(),
    );
    return () => clearTimeout(timeout);
  }, [reloading]);

  return reloading ? <Loader /> : <ErrorComponent {...props} />;
};

export default RouteErrorFallback;
