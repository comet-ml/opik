import React from "react";
import {
  BotMessageSquare,
  SquareDashedMousePointer,
  FileSliders,
  ExternalLink,
  type LucideIcon,
} from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";
import { buildDocsUrl } from "@/v2/lib/utils";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import emptyOptStudioLightUrl from "/images/empty-optimization-studio-light.svg";
import emptyOptStudioDarkUrl from "/images/empty-optimization-studio-dark.svg";

type OptimizationsEmptyStateProps = {
  onOptimizeViaSdkClick: () => void;
};

type ActionCardConfig = {
  icon: LucideIcon;
  iconClassName: string;
  title: string;
  description: string;
  onClick: () => void;
};

const ActionCard: React.FC<ActionCardConfig> = ({
  icon: Icon,
  iconClassName,
  title,
  description,
  onClick,
}) => (
  <button
    type="button"
    onClick={onClick}
    className="flex flex-col gap-1 rounded-md border p-4 text-left transition-colors hover:border-primary hover:bg-primary-100"
  >
    <span className="flex items-center gap-2">
      <Icon className={cn("size-4 shrink-0", iconClassName)} />
      <span className="comet-body-s-accented text-foreground">{title}</span>
    </span>
    <span className="comet-body-xs text-muted-slate">{description}</span>
  </button>
);

const OptimizationsEmptyState: React.FC<OptimizationsEmptyStateProps> = ({
  onOptimizeViaSdkClick,
}) => {
  const { themeMode } = useTheme();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const imageUrl =
    themeMode === THEME_MODE.DARK
      ? emptyOptStudioDarkUrl
      : emptyOptStudioLightUrl;

  const navigateToStudio = (templateId?: string) => {
    navigate({
      to: "/$workspaceName/projects/$projectId/optimizations/new",
      params: { workspaceName, projectId: activeProjectId! },
      search: templateId ? { template: templateId } : undefined,
    });
  };

  const actionCards: ActionCardConfig[] = [
    {
      icon: BotMessageSquare,
      iconClassName: "text-chart-blue",
      title: "Run a demo example",
      description:
        "Start with a pre-configured optimization example for a support chatbot.",
      onClick: () => navigateToStudio("opik-chatbot"),
    },
    {
      icon: SquareDashedMousePointer,
      iconClassName: "text-chart-purple",
      title: "Use the Optimization Studio",
      description:
        "Create a custom optimization workflow to test and improve your prompts.",
      onClick: () => navigateToStudio(),
    },
    {
      icon: FileSliders,
      iconClassName: "text-chart-burgundy",
      title: "Optimize via SDK",
      description:
        "Generate starter code for running a custom optimization programmatically.",
      onClick: onOptimizeViaSdkClick,
    },
  ];

  return (
    <div className="flex min-h-full flex-1 items-center justify-center gap-16 px-6">
      <div className="flex w-full max-w-[480px] flex-col gap-4">
        <h2 className="comet-title-s text-foreground">
          No optimization runs yet
        </h2>
        <p className="comet-body-s text-muted-slate">
          Try different prompt versions and see what performs best. Optimization
          runs help you improve accuracy, consistency, and user experience.
        </p>
        <div className="flex flex-col gap-2">
          {actionCards.map((card) => (
            <ActionCard key={card.title} {...card} />
          ))}
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
