import React from "react";
import { Link } from "@tanstack/react-router";
import {
  ArrowUpRight,
  Database,
  FileTerminal,
  FlaskConical,
  LayoutGrid,
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

export enum RESOURCE_TYPE {
  project,
  dataset,
  prompt,
  experiment,
  optimization,
  trial,
  annotationQueue,
}

export const RESOURCE_MAP = {
  [RESOURCE_TYPE.project]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: LayoutGrid,
    param: "projectId",
    deleted: "Deleted project",
    label: "project",
    color: "var(--color-blue)",
  },
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Deleted dataset",
    label: "dataset",
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
    color: "var(--color-green)",
  },
  [RESOURCE_TYPE.optimization]: {
    url: "/$workspaceName/optimizations/$datasetId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "optimization run",
    color: "var(--color-yellow)",
  },
  [RESOURCE_TYPE.trial]: {
    url: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "trial",
    color: "var(--color-yellow)",
  },
  [RESOURCE_TYPE.annotationQueue]: {
    url: "/$workspaceName/annotation-queues/$annotationQueueId",
    icon: UserPen,
    param: "annotationQueueId",
    deleted: "Deleted annotation queue",
    label: "annotation queue",
    color: "var(--color-pink)",
  },
};

type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[]>;
  params?: Record<string, string | number | string[]>;
  variant?: TagProps["variant"];
  iconsSize?: number;
  gapSize?: number;
  tooltipContent?: string;
  asTag?: boolean;
  isDeleted?: boolean;
};

const ResourceLink: React.FunctionComponent<ResourceLinkProps> = ({
  resource,
  name,
  id,
  search,
  params,
  variant = "gray",
  iconsSize = 4,
  gapSize = 2,
  tooltipContent = "",
  asTag = false,
  isDeleted = false,
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
      search={search}
      onClick={(event) => event.stopPropagation()}
      className="max-w-full"
      disabled={deleted}
    >
      {asTag ? (
        <TooltipWrapper content={tooltipContent || text} stopClickPropagation>
          <Tag
            size="md"
            variant={variant}
            className={cn(
              "flex items-center",
              gapSize === 1 && "gap-1",
              gapSize === 2 && "gap-2",
              gapSize === 3 && "gap-3",
              gapSize === 4 && "gap-4",
              deleted && "opacity-50 cursor-default",
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
