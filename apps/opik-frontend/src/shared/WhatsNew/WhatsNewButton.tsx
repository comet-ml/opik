import React from "react";
import { Sparkles } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";
import useWhatsNewSeen from "@/hooks/useWhatsNewSeen";
import useDeploymentVersionHistory from "@/api/deployment/useDeploymentVersionHistory";

/**
 * Top-bar entry point for the What's new panel. It carries the unread indicator itself: the
 * panel is the only place an upgrade is announced, so its trigger must be visible without
 * opening any menu.
 */
const WhatsNewButton: React.FC = () => {
  const { open } = useLayoutDialog("whats-new");
  const { data: versionHistory } = useDeploymentVersionHistory();
  const { hasUnseenRelease } = useWhatsNewSeen(versionHistory?.current_version);

  return (
    <TooltipWrapper content="What's new?">
      <Button
        variant="outline"
        size="icon-sm"
        className="relative"
        onClick={open}
        aria-label="What's new"
      >
        <Sparkles />
        {hasUnseenRelease && (
          <span className="absolute -right-0.5 -top-0.5 size-2 rounded-full bg-primary" />
        )}
      </Button>
    </TooltipWrapper>
  );
};

export default WhatsNewButton;
