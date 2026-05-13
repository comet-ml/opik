import React, { lazy, Suspense } from "react";

import { BridgeSurface } from "@/types/assistant-sidebar";

// Inlined (not imported from @/plugins/comet/*) so this wrapper has zero
// static dependency on the comet plugin tree. The lazy import below is the
// only path that ever pulls comet code into the bundle, and it only resolves
// when this component actually renders (i.e. IS_ASSISTANT_DEV is true).
const IS_ASSISTANT_DEV = Boolean(
  import.meta.env.VITE_ASSISTANT_SIDEBAR_BASE_URL,
);

interface Props {
  surface?: BridgeSurface;
  onWidthChange: (width: number) => void;
}

// When the dev flag is unset we export `undefined`, so `setupPlugins`'s
// `if (plugin.default)` check skips registration entirely. Consumers like
// `SideBarMenuItems` (showOlliePage = !!AssistantSidebar) and `PageLayout`
// (showAssistantSidebar = !!AssistantSidebar) then see `null` in the store
// and correctly hide the menu item and layout slot. Returning a component
// that renders null would still register a truthy reference and leak both.
let AssistantSidebar: React.FC<Props> | undefined;

if (IS_ASSISTANT_DEV) {
  const CometAssistantSidebar = lazy(
    () => import("@/plugins/comet/AssistantSidebar"),
  );

  const Wrapped: React.FC<Props> = (props) => (
    <Suspense fallback={null}>
      <CometAssistantSidebar {...props} />
    </Suspense>
  );
  Wrapped.displayName = "AssistantSidebar";
  AssistantSidebar = Wrapped;
}

export default AssistantSidebar;
