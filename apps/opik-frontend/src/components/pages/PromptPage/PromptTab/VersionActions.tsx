import React from "react";
import { Copy, Undo2 } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { PromptVersion } from "@/types/prompts";

interface VersionActionsProps {
  version: PromptVersion;
  latestVersionId?: string;
  isHovered: boolean;
  onCopyClick: (commit: string) => void;
  onRestoreVersionClick: (version: PromptVersion) => void;
}

const VersionActions: React.FC<VersionActionsProps> = ({
  version,
  latestVersionId,
  isHovered,
  onCopyClick,
  onRestoreVersionClick,
}) => {
  const canRestore = version.id !== latestVersionId;

  return (
    <div
      className={cn(
        "ml-auto flex gap-1 shrink-0 transition-opacity",
        isHovered ? "opacity-100" : "opacity-0",
      )}
    >
      <TooltipWrapper content="Copy commit">
        <Button
          size="icon-3xs"
          variant="minimal"
          onClick={(e) => {
            e.stopPropagation();
            onCopyClick(version.commit);
          }}
        >
          <Copy />
        </Button>
      </TooltipWrapper>
      {canRestore && (
        <TooltipWrapper content="Restore this version">
          <Button
            size="icon-3xs"
            variant="minimal"
            onClick={(e) => {
              e.stopPropagation();
              onRestoreVersionClick(version);
            }}
          >
            <Undo2 />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default VersionActions;
