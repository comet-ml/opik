import React, { lazy, Suspense } from "react";
import { ExplainButtonProps } from "@/types/assistant-sidebar";

const IS_ASSISTANT_DEV = Boolean(
  import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL,
);

let ExplainButton: React.FC<ExplainButtonProps> | undefined;

if (IS_ASSISTANT_DEV) {
  const CometExplainButton = lazy(
    () => import("@/plugins/comet/ExplainButton"),
  );
  const Wrapped: React.FC<ExplainButtonProps> = (props) => (
    <Suspense fallback={null}>
      <CometExplainButton {...props} />
    </Suspense>
  );
  Wrapped.displayName = "ExplainButton";
  ExplainButton = Wrapped;
}

export default ExplainButton;
