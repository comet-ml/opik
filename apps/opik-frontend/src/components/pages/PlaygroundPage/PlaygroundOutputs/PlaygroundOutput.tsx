import React, { useState, useMemo } from "react";
import { ListTree, GitCompare, FileText, ChevronDown } from "lucide-react";
import { diffWords, Change } from "diff";

import { cn } from "@/lib/utils";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import {
  useOutputLoadingByPromptDatasetItemId,
  useOutputStaleStatusByPromptDatasetItemId,
  useOutputValueByPromptDatasetItemId,
  useTraceIdByPromptDatasetItemId,
  useTraceContext,
} from "@/store/PlaygroundStore";
import { generateTracesURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";
import useProjectByName from "@/api/projects/useProjectByName";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

type OutputViewMode = "current" | "original" | "diff";

interface PlaygroundOutputProps {
  promptId: string;
  totalOutputs: number;
}

const PlaygroundOutput = ({
  promptId,
  totalOutputs,
}: PlaygroundOutputProps) => {
  const value = useOutputValueByPromptDatasetItemId(promptId);
  const isLoading = useOutputLoadingByPromptDatasetItemId(promptId);
  const stale = useOutputStaleStatusByPromptDatasetItemId(promptId);
  const traceId = useTraceIdByPromptDatasetItemId(promptId);
  const traceContext = useTraceContext();

  const [viewMode, setViewMode] = useState<OutputViewMode>("current");

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: playgroundProject } = useProjectByName(
    {
      projectName: PLAYGROUND_PROJECT_NAME,
    },
    {
      enabled: !!traceId && !!workspaceName,
    },
  );

  const originalOutput = traceContext?.originalOutput;
  const hasOriginalOutput = Boolean(originalOutput);
  const hasCurrentOutput = Boolean(value);
  const canShowDiff = hasOriginalOutput && hasCurrentOutput;

  // Compute diff changes for side-by-side view
  const diffChanges = useMemo(() => {
    if (!canShowDiff) return [];
    return diffWords(originalOutput || "", value || "");
  }, [originalOutput, value, canShowDiff]);

  // Render original text with removed parts highlighted
  const renderOriginalWithDiff = (changes: Change[]) => {
    return (
      <div className="whitespace-pre-wrap">
        {changes.map((change, index) => {
          if (change.added) {
            // Skip added parts in original view
            return null;
          }
          if (change.removed) {
            // Highlight removed parts
            return (
              <span
                key={index}
                className="rounded-sm bg-diff-removed-bg px-0.5 text-diff-removed-text line-through"
              >
                {change.value}
              </span>
            );
          }
          // Unchanged parts
          return <span key={index}>{change.value}</span>;
        })}
      </div>
    );
  };

  // Render current text with added parts highlighted
  const renderCurrentWithDiff = (changes: Change[]) => {
    return (
      <div className="whitespace-pre-wrap">
        {changes.map((change, index) => {
          if (change.removed) {
            // Skip removed parts in current view
            return null;
          }
          if (change.added) {
            // Highlight added parts
            return (
              <span
                key={index}
                className="rounded-sm bg-diff-added-bg px-0.5 text-diff-added-text"
              >
                {change.value}
              </span>
            );
          }
          // Unchanged parts
          return <span key={index}>{change.value}</span>;
        })}
      </div>
    );
  };

  const handleTraceLinkClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (traceId && playgroundProject?.id) {
      const url = generateTracesURL(
        workspaceName,
        playgroundProject.id,
        "traces",
        traceId,
      );
      window.open(url, "_blank");
    }
  };

  const renderViewModeLabel = () => {
    switch (viewMode) {
      case "original":
        return "Original Output";
      case "diff":
        return "Side by Side";
      default:
        return "Current Output";
    }
  };

  const renderContent = () => {
    if (isLoading && !value) {
      return <PlaygroundOutputLoader />;
    }

    // Show original output
    if (viewMode === "original" && hasOriginalOutput) {
      return (
        <div className="flex flex-col gap-2">
          <div className="comet-body-xs flex items-center gap-1 text-muted-slate">
            <FileText className="size-3" />
            <span>Original output from trace</span>
          </div>
          <MarkdownPreview className="text-muted-foreground">
            {originalOutput}
          </MarkdownPreview>
        </div>
      );
    }

    // Show side-by-side comparison view with diff highlighting
    if (viewMode === "diff" && canShowDiff) {
      return (
        <div className="flex h-full flex-col gap-3">
          <div className="grid h-full grid-cols-2 gap-4">
            {/* Original Output with removed parts highlighted */}
            <div className="flex flex-col gap-2">
              <div className="comet-body-xs flex items-center gap-1.5 font-medium text-muted-slate">
                <FileText className="size-3" />
                <span>Original (from trace)</span>
                <span className="ml-1 rounded bg-diff-removed-bg px-1 text-[10px] text-diff-removed-text">
                  removed
                </span>
              </div>
              <div className="comet-body-s flex-1 overflow-auto rounded-md border border-border bg-muted/20 p-3">
                {renderOriginalWithDiff(diffChanges)}
              </div>
            </div>
            {/* Current Output with added parts highlighted */}
            <div className="flex flex-col gap-2">
              <div className="comet-body-xs flex items-center gap-1.5 font-medium text-primary">
                <FileText className="size-3" />
                <span>Current (playground)</span>
                <span className="ml-1 rounded bg-diff-added-bg px-1 text-[10px] text-diff-added-text">
                  added
                </span>
              </div>
              <div className="comet-body-s flex-1 overflow-auto rounded-md border border-primary/30 bg-primary/5 p-3">
                {renderCurrentWithDiff(diffChanges)}
              </div>
            </div>
          </div>
        </div>
      );
    }

    // Default: show current output
    return (
      <MarkdownPreview
        className={cn({
          "text-muted-gray dark:text-foreground": stale,
        })}
      >
        {value}
      </MarkdownPreview>
    );
  };

  const outputLabel = totalOutputs === 1 ? "Output" : "Outputs";

  return (
    <div className="size-full min-w-[var(--min-prompt-width)]">
      <div className="my-3 flex items-center justify-between">
        <p className="comet-body-s-accented">{outputLabel}</p>

        {/* View mode selector - only show if we have original output from trace */}
        {hasOriginalOutput && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="sm"
                className="h-7 gap-1 text-xs"
              >
                {viewMode === "diff" && <GitCompare className="size-3" />}
                {viewMode === "original" && <FileText className="size-3" />}
                {viewMode === "current" && <FileText className="size-3" />}
                {renderViewModeLabel()}
                <ChevronDown className="size-3" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuRadioGroup
                value={viewMode}
                onValueChange={(v) => setViewMode(v as OutputViewMode)}
              >
                <DropdownMenuRadioItem value="current">
                  <FileText className="mr-2 size-3.5" />
                  Current Output
                </DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="original">
                  <FileText className="mr-2 size-3.5" />
                  Original Output (from trace)
                </DropdownMenuRadioItem>
                <DropdownMenuRadioItem
                  value="diff"
                  disabled={!canShowDiff}
                  className={cn({ "opacity-50": !canShowDiff })}
                >
                  <GitCompare className="mr-2 size-3.5" />
                  Side by Side
                  {!hasCurrentOutput && (
                    <span className="ml-1 text-xs text-muted-foreground">
                      - Run first
                    </span>
                  )}
                </DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
      <div className="comet-body-s group relative min-h-52 rounded-lg border bg-background p-3">
        {traceId && playgroundProject?.id && (
          <TooltipWrapper content="Click to open original trace">
            <Button
              size="icon-xs"
              variant="outline"
              onClick={handleTraceLinkClick}
              className="absolute right-4 top-4 hidden group-hover:flex"
            >
              <ListTree />
            </Button>
          </TooltipWrapper>
        )}
        {renderContent()}
      </div>
    </div>
  );
};

export default PlaygroundOutput;
