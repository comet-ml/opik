import { GitCommitVertical } from "lucide-react";
import copy from "clipboard-copy";
import { cn } from "@/lib/utils";

import { formatDate } from "@/lib/date";
import React, { useState } from "react";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";
import VersionTags from "@/components/pages/PromptPage/PromptTab/VersionTags";
import VersionActions from "@/components/pages/PromptPage/PromptTab/VersionActions";

interface CommitHistoryProps {
  versions: PromptVersion[];
  onVersionClick: (version: PromptVersion) => void;
  activeVersionId: string;
  onRestoreVersionClick: (version: PromptVersion) => void;
  latestVersionId?: string;
}

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
                {version.tags && version.tags.length > 0 && (
                  <>
                    <span className="shrink-0 text-xs text-muted-slate/60 transition-opacity">
                      ·
                    </span>
                    <VersionTags tags={version.tags} />
                  </>
                )}
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
