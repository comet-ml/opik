import { Copy, GitCommitVertical } from "lucide-react";
import copy from "clipboard-copy";
import { cn } from "@/lib/utils";

import { formatDate } from "@/lib/date";
import React, { useState } from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";

interface CommitHistoryProps {
  versions: PromptVersion[];
  onVersionClick: (version: PromptVersion) => void;
  activeVersionId: string;
}

const CommitHistory = ({
  versions,
  onVersionClick,
  activeVersionId,
}: CommitHistoryProps) => {
  const { toast } = useToast();
  const [hoveredVersionId, setHoveredVersionId] = useState<string | null>(null);

  const handleCopyClick = async (versionId: string) => {
    await copy(versionId);

    toast({
      description: "ID successfully copied to clipboard",
    });
  };

  return (
    <ul className="max-h-[500px] overflow-y-auto  rounded border bg-white p-1">
      {versions?.map((version) => {
        return (
          <li
            key={version.id}
            className={cn(
              "cursor-pointer hover:bg-muted rounded-sm px-4 py-2.5 flex flex-col",
              {
                "bg-muted": activeVersionId === version.id,
              },
            )}
            onMouseEnter={() => setHoveredVersionId(version.id)}
            onMouseLeave={() => setHoveredVersionId(null)}
            onClick={() => onVersionClick(version)}
          >
            <div className="flex items-center gap-2">
              <GitCommitVertical className="mt-auto size-4 shrink-0 text-muted-slate" />
              <span className="comet-body-s truncate">{version.commit}</span>
              {hoveredVersionId == version.id && (
                <TooltipWrapper content="Copy code">
                  <Button
                    size="icon-xxs"
                    variant="minimal"
                    onClick={() => handleCopyClick(version.commit)}
                  >
                    <Copy className="size-3 shrink-0" />
                  </Button>
                </TooltipWrapper>
              )}
            </div>
            <p className="comet-body-s pl-6 text-light-slate">
              {formatDate(version.created_at)}
            </p>
          </li>
        );
      })}
    </ul>
  );
};

export default CommitHistory;
