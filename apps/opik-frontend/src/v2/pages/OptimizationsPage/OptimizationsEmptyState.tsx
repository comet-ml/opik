import React from "react";
import { SquareDashedMousePointer, Bot, ExternalLink } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/v2/lib/utils";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import emptyOptStudioLightUrl from "/images/empty-optimization-studio-light.svg";
import emptyOptStudioDarkUrl from "/images/empty-optimization-studio-dark.svg";

type OptimizationsEmptyStateProps = {
  onOptimizeClick: () => void;
};

const OptimizationsEmptyState: React.FC<OptimizationsEmptyStateProps> = ({
  onOptimizeClick,
}) => {
  const { themeMode } = useTheme();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const imageUrl =
    themeMode === THEME_MODE.DARK
      ? emptyOptStudioDarkUrl
      : emptyOptStudioLightUrl;

  const handleDemoTemplateClick = () => {
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations/new",
      params: { workspaceName, projectId: activeProjectId! },
      search: { template: "opik-chatbot" },
    });
  };

  return (
    <div className="flex min-h-full flex-1 items-center justify-center gap-16 px-6">
      <div className="flex w-full max-w-lg flex-col gap-6">
        <div className="flex flex-col gap-2">
          <h2 className="comet-title-s text-foreground">
            No optimization runs yet
          </h2>
          <p className="comet-body-s text-muted-slate">
            Try different prompt versions and see what performs best.
            Optimization runs help you improve accuracy, consistency, and user
            experience.
          </p>
        </div>
        <div className="flex flex-col gap-3">
          <button
            type="button"
            onClick={onOptimizeClick}
            className="flex items-start gap-3 rounded-md border p-4 text-left transition-colors hover:border-primary hover:bg-primary-100"
          >
            <SquareDashedMousePointer className="mt-0.5 size-5 shrink-0 text-chart-green" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented text-foreground">
                Optimize your prompt
              </span>
              <span className="comet-body-s text-muted-slate">
                Start from scratch and configure your own optimization setting
              </span>
            </div>
          </button>
          <button
            type="button"
            onClick={handleDemoTemplateClick}
            className="flex items-start gap-3 rounded-md border p-4 text-left transition-colors hover:border-primary hover:bg-primary-100"
          >
            <Bot className="mt-0.5 size-5 shrink-0 text-chart-burgundy" />
            <div className="flex flex-col gap-0.5">
              <span className="comet-body-s-accented text-foreground">
                Try demo template
              </span>
              <span className="comet-body-s text-muted-slate">
                Train a chatbot to answer questions about Opik and decline
                off-topic requests.
              </span>
            </div>
          </button>
        </div>
        <div>
          <Button variant="outline" size="sm" asChild>
            <a
              href={buildDocsUrl(
                "/development/optimization-runs/optimization_studio",
              )}
              target="_blank"
              rel="noreferrer"
            >
              View docs
              <ExternalLink className="ml-1.5 size-3.5" />
            </a>
          </Button>
        </div>
      </div>

      <div className="hidden shrink-0 items-start xl:flex">
        <img src={imageUrl} alt="No optimization runs yet" />
      </div>
    </div>
  );
};

export default OptimizationsEmptyState;
