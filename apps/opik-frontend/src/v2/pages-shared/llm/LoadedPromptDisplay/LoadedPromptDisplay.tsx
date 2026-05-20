import React from "react";
import {
  FileText,
  GitCommitVertical,
  MessagesSquare,
  XCircle,
} from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import StageTag from "@/v2/pages-shared/version-history/StageTag";
import { pickHighestStage } from "@/utils/agent-configurations";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

type LoadedPromptDisplayProps = {
  name?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  versionLabel?: string;
  versionTags?: string[];
  hasUnsavedChanges?: boolean;
  onClear?: () => void;
};

const LoadedPromptDisplay: React.FC<LoadedPromptDisplayProps> = ({
  name,
  templateStructure,
  versionLabel,
  versionTags,
  hasUnsavedChanges = false,
  onClear,
}) => {
  const displayName = name ?? "Loaded prompt";
  const stage = pickHighestStage(versionTags);
  const Icon =
    templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT
      ? MessagesSquare
      : FileText;

  return (
    <div className="flex min-w-0 items-center gap-1 rounded-md border border-border bg-background px-1 text-[11px]">
      <TooltipWrapper
        content={hasUnsavedChanges ? "Unsaved changes" : displayName}
      >
        <div className="flex min-w-0 items-center gap-1">
          <Icon className="size-3 shrink-0 text-muted-slate" />
          <span className="truncate text-muted-slate">{displayName}</span>
          {hasUnsavedChanges && (
            <span className="size-1.5 shrink-0 rounded-full bg-warning" />
          )}
        </div>
      </TooltipWrapper>
      {versionLabel && (
        <span className="flex shrink-0 items-center gap-0.5 text-light-slate">
          <GitCommitVertical className="size-3 shrink-0" />
          {versionLabel}
        </span>
      )}
      {stage && <StageTag value={stage} size="xs" />}
      {onClear && (
        <TooltipWrapper content="Detach loaded prompt">
          <Button
            variant="minimal"
            size="icon-2xs"
            className="shrink-0"
            onClick={onClear}
          >
            <XCircle />
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default LoadedPromptDisplay;
