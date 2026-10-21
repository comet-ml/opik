import React from "react";

import { cn } from "@/lib/utils";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundStoppedTagProps {
  stale?: boolean;
  className?: string;
}

const PlaygroundStoppedTag: React.FC<PlaygroundStoppedTagProps> = ({
  stale = false,
  className,
}) => (
  <TooltipWrapper content="This run was stopped before the model finished. The answer may be cut off, and there is no token count.">
    <Tag
      data-testid="playground-output-stopped"
      variant="gray"
      className={cn(
        "shrink-0 cursor-default",
        stale && "opacity-50",
        className,
      )}
    >
      Stopped
    </Tag>
  </TooltipWrapper>
);

export default PlaygroundStoppedTag;
