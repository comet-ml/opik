import { Link, useParams } from "@tanstack/react-router";
import {
  FlaskConical,
  Database,
  ListChecks,
  AlertTriangle,
  Sparkles,
  Workflow,
  ArrowRight,
  LucideIcon,
} from "lucide-react";
import activityCloudIcon from "@/icons/activity-cloud.svg";
import { formatRelativeDateTime } from "@/lib/date";
import { Skeleton } from "@/ui/skeleton";
import useRecentActivity from "@/api/projects/useRecentActivity";
import { ActivityType, RecentActivityItem } from "@/types/recent-activity";

type ActivityConfigEntry = {
  label: string;
  icon: LucideIcon;
  color: string;
  getLink: (item: RecentActivityItem, base: string) => string;
};

const ACTIVITY_CONFIG: Record<ActivityType, ActivityConfigEntry> = {
  [ActivityType.OPTIMIZATION]: {
    label: "Optimization run created",
    icon: Sparkles,
    color: "text-chart-burgundy",
    getLink: (item, base) => `${base}/optimizations/${item.id}`,
  },
  [ActivityType.EXPERIMENT]: {
    label: "Experiment created",
    icon: FlaskConical,
    color: "text-chart-green",
    getLink: (item, base) =>
      `${base}/experiments/${item.resource_id}/compare?experiments=["${item.id}"]`,
  },
  [ActivityType.DATASET_VERSION]: {
    label: "Dataset updated",
    icon: Database,
    color: "text-chart-teal",
    getLink: (item, base) => `${base}/datasets/${item.id}/items`,
  },
  [ActivityType.TEST_SUITE_VERSION]: {
    label: "Test suite updated",
    icon: ListChecks,
    color: "text-chart-purple",
    getLink: (item, base) => `${base}/test-suites/${item.id}/items`,
  },
  [ActivityType.AGENT_CONFIG_VERSION]: {
    label: "Agent configuration created",
    icon: Workflow,
    color: "text-chart-blue",
    getLink: (item, base) => `${base}/agent-configuration?configId=${item.id}`,
  },
  [ActivityType.ALERT_EVENT]: {
    label: "Alert triggered",
    icon: AlertTriangle,
    color: "text-chart-red",
    getLink: (item, base) => `${base}/alerts/${item.id}`,
  },
};

function ActivityItem({ item }: { item: RecentActivityItem }) {
  const { workspaceName, projectId } = useParams({ strict: false }) as {
    workspaceName: string;
    projectId: string;
  };

  const config = ACTIVITY_CONFIG[item.type];
  const Icon = config.icon;
  const base = `/${workspaceName}/projects/${projectId}`;

  return (
    <Link
      to={config.getLink(item, base)}
      className="group flex w-full items-center gap-3 rounded-md border border-transparent px-3 py-2 text-left hover:border-primary hover:bg-primary/5"
    >
      <Icon className={`size-4 shrink-0 ${config.color}`} />
      <span className="comet-body-xs min-w-0 flex-1 truncate">
        <span className="comet-body-xs-accented">{config.label}:</span>{" "}
        <span className="text-muted-foreground">{item.name}</span>
      </span>
      <span className="shrink-0 text-xs text-muted-foreground">
        {formatRelativeDateTime(item.created_at)}
      </span>
      <ArrowRight className="size-4 shrink-0 text-muted-foreground opacity-0 group-hover:opacity-100" />
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
        We&apos;ll display recent activity on this project here
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
      <h2 className="comet-body-s-accented mb-3">Recent activity</h2>
      {isPending ? (
        <LoadingSkeleton />
      ) : !items.length ? (
        <EmptyState />
      ) : (
        <div className="flex flex-col">
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
