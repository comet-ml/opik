import { Link, LinkProps, useParams } from "@tanstack/react-router";
import {
  FlaskConical,
  Database,
  ListChecks,
  AlertTriangle,
  Sparkles,
  FileTerminal,
  ArrowRight,
  ListTree,
  LucideIcon,
} from "lucide-react";
import lowerFirst from "lodash/lowerFirst";
import activityCloudIcon from "@/icons/activity-cloud.svg";
import { formatRelativeDateTime } from "@/lib/date";
import { Skeleton } from "@/ui/skeleton";
import useRecentActivity from "@/api/projects/useRecentActivity";
import { ActivityType, RecentActivityItem } from "@/types/recent-activity";

type LinkParams = { workspaceName: string; projectId: string };

// The workspace and project are passed as router params rather than
// concatenated into `to`. TanStack strips the router basepath from `to` with an
// unbounded `pathname.replace(basepath, "")` (no boundary), so a literal
// `to: "/opik-testing/..."` under basepath `/opik` gets mangled to
// `/opik/-testing/...`. Deferring substitution to params runs after the strip.
type ActivityConfigEntry = {
  label: string | ((item: RecentActivityItem) => string);
  icon: LucideIcon;
  color: string;
  getLink: (item: RecentActivityItem, params: LinkParams) => LinkProps;
};

const ACTIVITY_CONFIG: Record<ActivityType, ActivityConfigEntry> = {
  [ActivityType.TRACE_DAILY]: {
    label: (item) =>
      `Traces logged ${lowerFirst(
        formatRelativeDateTime(item.created_at, false),
      )}`,
    icon: ListTree,
    color: "text-chart-teal",
    getLink: (_item, params) => ({
      to: "/$workspaceName/projects/$projectId/logs",
      params,
      search: { logsType: "traces" },
    }),
  },
  [ActivityType.OPTIMIZATION]: {
    label: "Optimization run created",
    icon: Sparkles,
    color: "text-chart-burgundy",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/optimizations/$optimizationId",
      params: { ...params, optimizationId: item.id },
    }),
  },
  [ActivityType.EXPERIMENT]: {
    label: "Experiment created",
    icon: FlaskConical,
    color: "text-chart-green",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
      params: { ...params, datasetId: item.resource_id },
      search: { experiments: [item.id] },
    }),
  },
  [ActivityType.DATASET_VERSION]: {
    label: "Dataset updated",
    icon: Database,
    color: "text-chart-purple",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/datasets/$datasetId/items",
      params: { ...params, datasetId: item.id },
    }),
  },
  [ActivityType.TEST_SUITE_VERSION]: {
    label: "Test suite updated",
    icon: ListChecks,
    color: "text-chart-purple",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/test-suites/$suiteId/items",
      params: { ...params, suiteId: item.id },
    }),
  },
  [ActivityType.PROMPT_VERSION]: {
    label: "Prompt created",
    icon: FileTerminal,
    color: "text-chart-blue",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/prompts/$promptId",
      params: { ...params, promptId: item.id },
    }),
  },
  [ActivityType.ALERT_EVENT]: {
    label: "Alert triggered",
    icon: AlertTriangle,
    color: "text-chart-red",
    getLink: (item, params) => ({
      to: "/$workspaceName/projects/$projectId/alerts/$alertId",
      params: { ...params, alertId: item.id },
    }),
  },
};

function ActivityItem({ item }: { item: RecentActivityItem }) {
  const { workspaceName, projectId } = useParams({ strict: false }) as {
    workspaceName: string;
    projectId: string;
  };

  const config = ACTIVITY_CONFIG[item.type];
  const Icon = config.icon;

  return (
    <Link
      {...config.getLink(item, { workspaceName, projectId })}
      className="group flex w-full items-center gap-3 rounded-md border px-3 py-2 text-left hover:border-primary hover:bg-primary/5"
    >
      <Icon className={`size-4 shrink-0 ${config.color}`} />
      <span className="comet-body-xs min-w-0 flex-1 truncate">
        <span className="comet-body-xs-accented">
          {typeof config.label === "function"
            ? config.label(item)
            : config.label}
          :
        </span>{" "}
        <span className="text-muted-foreground">{item.name}</span>
      </span>
      <span className="shrink-0 text-xs text-muted-foreground group-hover:hidden">
        {formatRelativeDateTime(
          item.created_at,
          item.type !== ActivityType.TRACE_DAILY,
        )}
      </span>
      <ArrowRight className="hidden size-4 shrink-0 text-primary group-hover:block" />
    </Link>
  );
}

function LoadingSkeleton() {
  return (
    <div className="flex flex-col gap-2">
      {Array.from({ length: 5 }).map((_, i) => (
        <Skeleton key={i} className="h-10 w-full rounded-md" />
      ))}
    </div>
  );
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border bg-background py-12">
      <img src={activityCloudIcon} alt="" className="mb-4 size-10" />
      <p className="text-sm font-medium">No activity yet</p>
      <p className="mt-2 text-xs text-muted-foreground">
        Tracks meaningful activity in this project — new artifacts, updates, and
        triggered alerts.
      </p>
    </div>
  );
}

export default function RecentActivitySection() {
  const { projectId } = useParams({ strict: false }) as {
    projectId: string;
  };

  const { data, isPending } = useRecentActivity({ projectId });

  const items = data?.content ?? [];

  return (
    <section>
      <h2 className="comet-body-s-accented mb-1.5">Recent activity</h2>
      {isPending ? (
        <LoadingSkeleton />
      ) : !items.length ? (
        <EmptyState />
      ) : (
        <div className="flex flex-col gap-1">
          {items.map((item) => (
            <ActivityItem
              key={`${item.type}-${item.id}-${item.created_at}`}
              item={item}
            />
          ))}
        </div>
      )}
    </section>
  );
}
