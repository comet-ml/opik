import { Copy, GitCommitVertical, Undo2 } from "lucide-react";
import copy from "clipboard-copy";
import { cn } from "@/lib/utils";

import { formatDate } from "@/lib/date";
import React, { useState } from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import TagListTooltipContent from "@/components/shared/TagListTooltipContent/TagListTooltipContent";
import { useVisibleTags } from "@/hooks/useVisibleTags";

interface CommitHistoryProps {
  versions: PromptVersion[];
  onVersionClick: (version: PromptVersion) => void;
  activeVersionId: string;
  onRestoreVersionClick: (version: PromptVersion) => void;
  latestVersionId?: string;
}

interface VersionTagsProps {
  tags: string[];
}

interface VersionActionsProps {
  version: PromptVersion;
  latestVersionId?: string;
  isHovered: boolean;
  onCopyClick: (commit: string) => void;
  onRestoreVersionClick: (version: PromptVersion) => void;
}

const VersionTags: React.FC<VersionTagsProps> = ({ tags }) => {
  const { visibleItems, hiddenItems, hasMoreItems, remainingCount } =
    useVisibleTags(tags);

  if (!tags || tags.length === 0) return null;

  return (
    <>
      <span
        className={cn(
          "text-muted-slate/60 shrink-0 text-xs transition-opacity",
          tags.length > 0 ? "opacity-100" : "opacity-0",
        )}
      >
        ·
      </span>
      <div className="flex max-w-[160px] shrink flex-nowrap items-center gap-0.5 overflow-hidden">
        {visibleItems.map((tag) => (
          <ColoredTag
            key={tag}
            label={tag}
            size="sm"
            className="min-w-0 max-w-[65px] shrink origin-left scale-[0.85] truncate"
          />
        ))}
        {hasMoreItems && (
          <TooltipWrapper
            content={<TagListTooltipContent tags={hiddenItems} />}
          >
            <div className="comet-body-s-accented flex h-4 items-center rounded-md border border-border pl-1 pr-1.5 text-[9px] text-muted-slate">
              +{remainingCount}
            </div>
          </TooltipWrapper>
        )}
      </div>
    </>
  );
};

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

const CommitHistory = ({
  versions,
  onVersionClick,
  activeVersionId,
  onRestoreVersionClick,
  latestVersionId,
}: CommitHistoryProps) => {
  const { toast } = useToast();
  const [hoveredVersionId, setHoveredVersionId] = useState<string | null>(null);

  const handleCopyClick = async (versionId: string) => {
    await copy(versionId);

    toast({
      description: "Commit successfully copied to clipboard",
    });
  };

  return (
    <>
      <ul className="max-h-[500px] overflow-y-auto overflow-x-hidden rounded border bg-background p-1">
        {versions?.map((version) => {
          const isActive = activeVersionId === version.id;
          const isHovered = hoveredVersionId === version.id;

          return (
            <li
              key={version.id}
              className={cn(
                "cursor-pointer hover:bg-primary-foreground rounded-sm px-4 py-2.5 flex flex-col overflow-hidden",
                {
                  "bg-primary-foreground": isActive,
                },
              )}
              onMouseEnter={() => setHoveredVersionId(version.id)}
              onMouseLeave={() => setHoveredVersionId(null)}
              onClick={() => onVersionClick(version)}
            >
              <div className="flex h-[24px] min-w-0 items-center gap-2">
                <GitCommitVertical className="size-4 shrink-0 text-muted-slate" />
                <span
                  className={cn("comet-body-s shrink-0", {
                    "comet-body-s-accented": isActive,
                  })}
                >
                  {version.commit}
                </span>
                <VersionTags tags={version.tags || []} />
                <VersionActions
                  version={version}
                  latestVersionId={latestVersionId}
                  isHovered={isHovered}
                  onCopyClick={handleCopyClick}
                  onRestoreVersionClick={onRestoreVersionClick}
                />
              </div>
              <p className="comet-body-s pl-6 text-light-slate">
                {formatDate(version.created_at)}
              </p>
            </li>
          );
        })}
      </ul>
    </>
  );
};

export default CommitHistory;
