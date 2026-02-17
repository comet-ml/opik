import React from "react";
import { Link } from "@tanstack/react-router";
import {
  ArrowUpRight,
  ChartLine,
  Database,
  FileTerminal,
  FlaskConical,
  LayoutGrid,
  ListTree,
  MessagesSquare,
  SparklesIcon,
  UserPen,
} from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { TagProps } from "@/components/ui/tag";
import { Filter } from "@/types/filters";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";

export enum RESOURCE_TYPE {
  project,
  dataset,
  datasetItem,
  prompt,
  experiment,
  experimentItem,
  optimization,
  trial,
  annotationQueue,
  dashboard,
  traces,
  threads,
}

export const RESOURCE_MAP = {
  [RESOURCE_TYPE.project]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: LayoutGrid,
    param: "projectId",
    deleted: "Deleted project",
    label: "project",
    color: "var(--color-green)",
  },
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Deleted dataset",
    label: "dataset",
    color: "var(--color-yellow)",
  },
  [RESOURCE_TYPE.datasetItem]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Deleted dataset item",
    label: "dataset item",
    color: "var(--color-yellow)",
  },
  [RESOURCE_TYPE.prompt]: {
    url: "/$workspaceName/prompts/$promptId",
    icon: FileTerminal,
    param: "promptId",
    deleted: "Deleted prompt",
    label: "prompt",
    color: "var(--color-purple)",
  },
  [RESOURCE_TYPE.experiment]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    icon: FlaskConical,
    param: "datasetId",
    deleted: "Deleted experiment",
    label: "experiment",
    color: "var(--color-burgundy)",
  },
  [RESOURCE_TYPE.experimentItem]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    icon: FlaskConical,
    param: "datasetId",
    deleted: "Deleted experiment item",
    label: "experiment item",
    color: "var(--color-burgundy)",
  },
  [RESOURCE_TYPE.optimization]: {
    url: "/$workspaceName/optimizations/$datasetId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "optimization run",
    color: "var(--color-orange)",
  },
  [RESOURCE_TYPE.trial]: {
    url: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "trial",
    color: "var(--color-orange)",
  },
  [RESOURCE_TYPE.annotationQueue]: {
    url: "/$workspaceName/annotation-queues/$annotationQueueId",
    icon: UserPen,
    param: "annotationQueueId",
    deleted: "Deleted annotation queue",
    label: "annotation queue",
    color: "var(--color-pink)",
  },
  [RESOURCE_TYPE.dashboard]: {
    url: "/$workspaceName/dashboards/$dashboardId",
    icon: ChartLine,
    param: "dashboardId",
    deleted: "Deleted dashboard",
    label: "dashboard",
    color: "var(--color-blue)",
  },
  [RESOURCE_TYPE.traces]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: ListTree,
    param: "projectId",
    deleted: "Deleted traces",
    label: "traces",
    color: "var(--color-green)",
    search: { tab: PROJECT_TAB.logs, logsType: LOGS_TYPE.traces },
  },
  [RESOURCE_TYPE.threads]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: MessagesSquare,
    param: "projectId",
    deleted: "Deleted threads",
    label: "threads",
    color: "var(--thread-icon-text)",
    search: { tab: PROJECT_TAB.logs, logsType: LOGS_TYPE.threads },
  },
};

type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  params?: Record<string, string | number | string[]>;
  variant?: TagProps["variant"];
  size?: TagProps["size"];
  iconsSize?: number;
  gapSize?: number;
  tooltipContent?: string;
  asTag?: boolean;
  isSmall?: boolean;
  isDeleted?: boolean;
  className?: string;
};

const ResourceLink: React.FunctionComponent<ResourceLinkProps> = ({
  resource,
  name,
  id,
  search,
  params,
  variant = "gray",
  size = "md",
  iconsSize = 4,
  gapSize = 2,
  tooltipContent = "",
  asTag = false,
  isSmall = false,
  isDeleted = false,
  className,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const props = RESOURCE_MAP[resource];
  const linkParams: Record<string, string> = {
    workspaceName,
    ...params,
  };
  linkParams[props.param] = id;

  const deleted = isUndefined(name) || isDeleted;
  const text = deleted ? props.deleted : name;

  return (
    <Link
      to={props.url}
      params={linkParams}
      search={{ ...("search" in props ? props.search : {}), ...search }}
      onClick={(event) => event.stopPropagation()}
      className="max-w-full"
      disabled={deleted}
    >
      {asTag ? (
        <TooltipWrapper content={tooltipContent || text} stopClickPropagation>
          <Tag
            size={size}
            variant={variant}
            className={cn(
              "flex items-center",
              gapSize === 1 && "gap-1",
              gapSize === 2 && "gap-2",
              gapSize === 3 && "gap-3",
              gapSize === 4 && "gap-4",
              deleted && "opacity-50 cursor-default",
              isSmall && "size-8 justify-center",
              className,
            )}
          >
            <props.icon
              className={cn(
                "shrink-0",
                iconsSize === 3 && "size-3",
                iconsSize === 4 && "size-4",
                iconsSize === 5 && "size-5",
              )}
              style={{ color: props.color }}
            />
            {!isSmall && (
              <>
                <div
                  className={cn(
                    "truncate",
                    variant === "transparent" && [
                      "text-muted-slate",
                      "comet-body-s-accented",
                    ],
                  )}
                >
                  {text}
                </div>
                {!deleted && (
                  <ArrowUpRight
                    className={cn(
                      "shrink-0 text-muted-slate",
                      iconsSize === 3 && "size-3",
                      iconsSize === 4 && "size-4",
                      iconsSize === 5 && "size-5",
                    )}
                  />
                )}
              </>
            )}
          </Tag>
        </TooltipWrapper>
      ) : (
        <Button
          variant="tableLink"
          size="sm"
          disabled={deleted}
          className="block truncate px-0 leading-8"
          asChild
        >
          <span>{text}</span>
        </Button>
      )}
    </Link>
  );
};

export default ResourceLink;
