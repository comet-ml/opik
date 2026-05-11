import React, { lazy, Suspense } from "react";

import { BridgeSurface } from "@/types/assistant-sidebar";

// Inlined (not imported from @/plugins/comet/*) so this wrapper has zero
// static dependency on the comet plugin tree. The lazy import below is the
// only path that ever pulls comet code into the bundle, and it only resolves
// when this component actually renders (i.e. IS_ASSISTANT_DEV is true).
const IS_ASSISTANT_DEV = Boolean(
  import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL,
);

const CometAssistantSidebar = lazy(
  () => import("@/plugins/comet/AssistantSidebar"),
);

interface Props {
  surface?: BridgeSurface;
  onWidthChange: (width: number) => void;
}

const AssistantSidebar: React.FC<Props> = (props) => {
  if (!IS_ASSISTANT_DEV) return null;
  return (
    <Suspense fallback={null}>
      <CometAssistantSidebar {...props} />
    </Suspense>
  );
};

export default AssistantSidebar;
