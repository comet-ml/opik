import React from "react";
import { formatDistanceToNow } from "date-fns";
import { Rocket, Wrench, RotateCcw, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { DeploymentVersion, DeploymentChangeType } from "@/types/blueprints";
import { Button } from "@/components/ui/button";
import EnvironmentBadge from "./EnvironmentBadge";

type BlueprintTimelineItemProps = {
  version: DeploymentVersion;
  envs: string[];
  onPromoteClick: () => void;
  onViewClick: () => void;
  canPromote: boolean;
};

const changeTypeIcons: Record<DeploymentChangeType, React.ElementType> = {
  optimizer: Rocket,
  manual: Wrench,
  rollback: RotateCcw,
};

const BlueprintTimelineItem: React.FC<BlueprintTimelineItemProps> = ({
  version,
  envs,
  onPromoteClick,
  onViewClick,
  canPromote,
}) => {
  const Icon = changeTypeIcons[version.change_type] || Wrench;
  const timeAgo = formatDistanceToNow(new Date(version.created_at), {
    addSuffix: true,
  });

  const isLatest = envs.includes("latest");
  const isProd = envs.includes("prod");

  return (
    <div className="relative flex items-start gap-4 py-3">
      {/* Environment pointers column */}
      <div className="flex w-20 shrink-0 flex-col items-end gap-1">
        {envs.map((env) => (
          <EnvironmentBadge key={env} env={env} />
        ))}
      </div>

      {/* Timeline line */}
      <div className="relative flex flex-col items-center">
        <div
          className={cn(
            "flex size-8 items-center justify-center rounded-full border-2",
            isProd
              ? "border-green-500 bg-green-50 text-green-600 dark:bg-green-900/30"
              : isLatest
                ? "border-blue-500 bg-blue-50 text-blue-600 dark:bg-blue-900/30"
                : "border-border bg-muted text-muted-slate",
          )}
        >
          <Icon className="size-4" />
        </div>
        {/* Vertical line connecting items */}
        <div className="absolute left-1/2 top-10 h-full w-0.5 -translate-x-1/2 bg-border" />
      </div>

      {/* Content */}
      <div className="flex min-w-0 flex-1 items-center justify-between gap-4">
        <button
          onClick={onViewClick}
          className="min-w-0 flex-1 text-left hover:opacity-80"
        >
          <div className="flex items-center gap-2">
            <span className="font-mono text-sm font-medium">
              v{version.version_number}
            </span>
            <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-slate">
              {version.change_type}
            </span>
          </div>
          <p className="mt-0.5 truncate text-sm text-muted-slate">
            {version.change_summary || "No description"}
          </p>
          <p className="mt-0.5 text-xs text-muted-slate/70">{timeAgo}</p>
        </button>

        {/* Actions */}
        <div className="flex items-center gap-2">
          {canPromote && (
            <Button variant="outline" size="sm" onClick={onPromoteClick}>
              Set as PROD
            </Button>
          )}
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={onViewClick}
            title="View version details"
          >
            <ChevronRight className="size-4" />
          </Button>
        </div>
      </div>
    </div>
  );
};

export default BlueprintTimelineItem;
