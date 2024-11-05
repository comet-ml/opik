import { Copy, GitCommitVertical } from "lucide-react";
import copy from "clipboard-copy";

import { formatDate } from "@/lib/date";
import React, { useState } from "react";
import { CompactPromptVersion } from "@/types/prompts";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";

interface CommitHistoryProps {
  versions: CompactPromptVersion[];
}

const CommitHistory = ({ versions }: CommitHistoryProps) => {
  const { toast } = useToast();
  const [hoveredVersionId, setHoveredVersionId] = useState<string | null>(null);

  const handleCopyClick = async (versionId: string) => {
    await copy(versionId);

    toast({
      description: "ID successfully copied to clipboard",
    });
  };

  return (
    <ul className="rounded border p-1 bg-white max-h-[500px] overflow-y-auto">
      {versions?.map((version) => {
        return (
          <li
            key={version.id}
            className="cursor-pointer hover:bg-muted rounded-sm px-4 py-2.5 flex flex-col"
            onMouseEnter={() => setHoveredVersionId(version.id)}
            onMouseLeave={() => setHoveredVersionId(null)}
          >
            <div className="flex items-center gap-2">
              <GitCommitVertical className="size-4 shrink-0 text-muted-slate mt-auto" />
              <span className="comet-body-s truncate">{version.id}</span>
              {hoveredVersionId == version.id && (
                <TooltipWrapper content="Copy code">
                  <Button
                    size="icon-xxs"
                    variant="minimal"
                    onClick={() => handleCopyClick(version.id)}
                  >
                    <Copy className="size-3 shrink-0" />
                  </Button>
                </TooltipWrapper>
              )}
            </div>
            <p className="comet-body-s pl-6 whitespace-pre-line break-words text-light-slate">
              {formatDate(version.created_at)}
            </p>
          </li>
        );
      })}
    </ul>
  );
};

export default CommitHistory;
