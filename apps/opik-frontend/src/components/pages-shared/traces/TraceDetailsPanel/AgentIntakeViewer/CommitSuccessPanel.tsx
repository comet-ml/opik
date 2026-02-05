import React, { useState } from "react";
import { Link } from "@tanstack/react-router";
import { Check, X, Copy, ExternalLink, Rocket } from "lucide-react";
import { CommitResult } from "@/api/agent-intake/useOptimizeStreaming";
import { useToast } from "@/components/ui/use-toast";
import useAppStore from "@/store/AppStore";
import { Button } from "@/components/ui/button";
import usePromoteToProd from "@/api/blueprints/usePromoteToProd";

type CommitSuccessPanelProps = {
  result: CommitResult;
};

const CommitSuccessPanel: React.FC<CommitSuccessPanelProps> = ({
  result,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();
  const [promoted, setPromoted] = useState(false);
  const promoteMutation = usePromoteToProd();

  const copyCommit = (commit: string) => {
    navigator.clipboard.writeText(commit);
    toast({
      title: "Copied",
      description: `Commit ${commit} copied to clipboard`,
    });
  };

  const handlePromote = async () => {
    if (!result.blueprint_id || !result.deployment_version) return;

    try {
      await promoteMutation.mutateAsync({
        blueprintId: result.blueprint_id,
        versionNumber: result.deployment_version,
      });
      setPromoted(true);
      toast({
        title: "Promoted to PROD",
        description: `Version ${result.deployment_version} is now live in production.`,
      });
    } catch {
      toast({
        title: "Promotion failed",
        description: "Could not promote to PROD. Try again from the Blueprints page.",
        variant: "destructive",
      });
    }
  };

  const hasDeploymentVersion = result.deployment_version !== undefined;

  return (
    <div className="rounded-lg border bg-background">
      <div className="border-b px-4 py-3">
        <div className="comet-body-s-accented mb-1 flex items-center gap-2 text-foreground">
          {result.all_success ? "Prompts Saved" : "Partially Saved"}
          {hasDeploymentVersion && (
            <span className="inline-flex items-center gap-1 rounded bg-blue-100 px-1.5 py-0.5 text-xs font-medium uppercase text-blue-700 dark:bg-blue-900/30 dark:text-blue-400">
              latest v{result.deployment_version}
            </span>
          )}
        </div>
        {result.all_success ? (
          <div className="comet-body-s text-muted-slate">
            {hasDeploymentVersion
              ? "Added as latest version. Promote to make it live in production."
              : "These prompts are now live in production."}
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

      {/* Promote to PROD action */}
      {hasDeploymentVersion && result.blueprint_id && (
        <div className="border-t px-4 py-3">
          {promoted ? (
            <div className="flex items-center gap-2 text-sm text-green-600">
              <Check className="size-4" />
              <span>Promoted to PROD (v{result.deployment_version})</span>
            </div>
          ) : (
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-slate">
                Ready to deploy to production?
              </span>
              <Button
                size="sm"
                onClick={handlePromote}
                disabled={promoteMutation.isPending}
              >
                <Rocket className="mr-1.5 size-3.5" />
                {promoteMutation.isPending ? "Promoting..." : "Promote to PROD"}
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default CommitSuccessPanel;
