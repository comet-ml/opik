import { Copy, GitCommitVertical, Undo2 } from "lucide-react";
import copy from "clipboard-copy";
import { cn } from "@/lib/utils";

import { formatDate } from "@/lib/date";
import React, { useState } from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import { PromptVersion } from "@/types/prompts";
import useRestorePromptVersionMutation from "@/api/prompts/useRestorePromptVersionMutation";

interface CommitHistoryProps {
  versions: PromptVersion[];
  onVersionClick: (version: PromptVersion) => void;
  activeVersionId: string;
  promptId: string;
}

const CommitHistory = ({
  versions,
  onVersionClick,
  activeVersionId,
  promptId,
}: CommitHistoryProps) => {
  const { toast } = useToast();
  const [hoveredVersionId, setHoveredVersionId] = useState<string | null>(null);
  const [restoreDialogOpen, setRestoreDialogOpen] = useState(false);
  const [versionToRestore, setVersionToRestore] = useState<PromptVersion | null>(null);
  
  const restorePromptVersionMutation = useRestorePromptVersionMutation();

  const handleCopyClick = async (versionId: string) => {
    await copy(versionId);

    toast({
      description: "Commit successfully copied to clipboard",
    });
  };

  const handleRestoreClick = (version: PromptVersion) => {
    setVersionToRestore(version);
    setRestoreDialogOpen(true);
  };

  const handleRestoreConfirm = () => {
    if (versionToRestore) {
      restorePromptVersionMutation.mutate({
        promptId,
        versionId: versionToRestore.id,
        onSuccess: (restoredVersion: PromptVersion) => {
          toast({
            description: `Version ${versionToRestore.commit} has been restored successfully`,
          });
          onVersionClick(restoredVersion);
        },
      });
    }
    setRestoreDialogOpen(false);
    setVersionToRestore(null);
  };

  const handleRestoreCancel = () => {
    setRestoreDialogOpen(false);
    setVersionToRestore(null);
  };

  return (
    <>
      <ul className="max-h-[500px] overflow-y-auto rounded border bg-background p-1">
        <h2 className="comet-title-s">Commit history</h2>
        {versions?.map((version) => {
          return (
            <li
              key={version.id}
              className={cn(
                "cursor-pointer hover:bg-primary-foreground rounded-sm px-4 py-2.5 flex flex-col",
                {
                  "bg-primary-foreground": activeVersionId === version.id,
                },
              )}
              onMouseEnter={() => setHoveredVersionId(version.id)}
              onMouseLeave={() => setHoveredVersionId(null)}
              onClick={() => onVersionClick(version)}
            >
              <div className="flex items-center gap-2">
                <GitCommitVertical className="mt-auto size-4 shrink-0 text-muted-slate" />
                <span
                  className={cn("comet-body-s truncate", {
                    "comet-body-s-accented": activeVersionId === version.id,
                  })}
                >
                  {version.commit}
                </span>
                {hoveredVersionId == version.id && (
                  <div className="flex gap-1">
                    <TooltipWrapper content="Copy code">
                      <Button
                        size="icon-3xs"
                        variant="minimal"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleCopyClick(version.commit);
                        }}
                      >
                        <Copy />
                      </Button>
                    </TooltipWrapper>
                    <TooltipWrapper content="Restore this version">
                      <Button
                        size="icon-3xs"
                        variant="minimal"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleRestoreClick(version);
                        }}
                      >
                        <Undo2 />
                      </Button>
                    </TooltipWrapper>
                  </div>
                )}
              </div>
              <p className="comet-body-s pl-6 text-light-slate">
                {formatDate(version.created_at)}
              </p>
            </li>
          );
        })}
      </ul>
      
      <Dialog open={restoreDialogOpen} onOpenChange={setRestoreDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Restore Version</DialogTitle>
            <DialogDescription>
              Are you sure you want to restore version {versionToRestore?.commit}? This will create a new version with the content from {versionToRestore?.commit}.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={handleRestoreCancel}>
              Cancel
            </Button>
            <Button 
              onClick={handleRestoreConfirm}
              disabled={restorePromptVersionMutation.isPending}
            >
              {restorePromptVersionMutation.isPending ? "Restoring..." : "Restore"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
};

export default CommitHistory;
