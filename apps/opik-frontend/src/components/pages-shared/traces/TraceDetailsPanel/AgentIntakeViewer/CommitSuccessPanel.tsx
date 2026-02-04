import React from "react";
import { Link } from "@tanstack/react-router";
import { Check, X, Copy, ExternalLink } from "lucide-react";
import { CommitResult } from "@/api/agent-intake/useOptimizeStreaming";
import { useToast } from "@/components/ui/use-toast";
import useAppStore from "@/store/AppStore";

type CommitSuccessPanelProps = {
  result: CommitResult;
};

const CommitSuccessPanel: React.FC<CommitSuccessPanelProps> = ({
  result,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const hasErrors = result.errors.length > 0;

  const copyCommit = (commit: string) => {
    navigator.clipboard.writeText(commit);
    toast({
      title: "Copied",
      description: `Commit ${commit} copied to clipboard`,
    });
  };

  return (
    <div className="rounded-lg border bg-background">
      <div className="border-b px-4 py-3">
        <div className="comet-body-s-accented mb-1 text-foreground">
          {result.all_success ? "Prompts Saved" : "Partially Saved"}
        </div>
        {result.all_success ? (
          <div className="comet-body-s text-muted-slate">
            These prompts are now live in production.
          </div>
        ) : (
          <div className="comet-body-s text-amber-600">
            Some prompts could not be saved.
          </div>
        )}
      </div>

      <div className="divide-y">
        {result.committed.map((item) => (
          <div
            key={item.prompt_name}
            className="flex items-center gap-3 px-4 py-2.5"
          >
            <Check className="size-4 shrink-0 text-green-600" />
            <div className="flex flex-1 items-center gap-2">
              <span className="text-sm text-foreground">
                {item.prompt_name}
              </span>
              {item.prompt_id && workspaceName && (
                <Link
                  to="/$workspaceName/prompts/$promptId"
                  params={{ workspaceName, promptId: item.prompt_id }}
                  search={item.version_id ? { tab: "prompt", activeVersionId: item.version_id } : undefined}
                  target="_blank"
                  onClick={(e) => e.stopPropagation()}
                  className="text-muted-slate hover:text-foreground"
                  title="View prompt in new tab"
                >
                  <ExternalLink className="size-3.5" />
                </Link>
              )}
            </div>
            <button
              type="button"
              onClick={() => copyCommit(item.commit)}
              className="flex items-center gap-1.5 rounded bg-muted/50 px-2 py-1 font-mono text-xs text-muted-slate hover:bg-muted"
              title="Click to copy"
            >
              <span>{item.commit}</span>
              <Copy className="size-3" />
            </button>
          </div>
        ))}

        {result.errors.map((item) => (
          <div
            key={item.prompt_name}
            className="flex items-center gap-3 px-4 py-2.5"
          >
            <X className="size-4 shrink-0 text-red-500" />
            <div className="flex-1">
              <div className="text-sm text-foreground">{item.prompt_name}</div>
              <div className="text-xs text-red-500">{item.error}</div>
            </div>
          </div>
        ))}
      </div>

    </div>
  );
};

export default CommitSuccessPanel;
