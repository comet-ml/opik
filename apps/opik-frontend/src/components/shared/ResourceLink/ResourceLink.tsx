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
import { Button } from "@/components/ui/button";
import { RESOURCE_TYPE, RESOURCE_COLORS } from "@/constants/colors";

export { RESOURCE_TYPE } from "@/constants/colors";

export const RESOURCE_MAP = {
  [RESOURCE_TYPE.project]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: LayoutGrid,
    param: "projectId",
    deleted: "Deleted project",
    label: "project",
    color: RESOURCE_COLORS[RESOURCE_TYPE.project],
  },
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Deleted dataset",
    label: "dataset",
    color: RESOURCE_COLORS[RESOURCE_TYPE.dataset],
  },
  [RESOURCE_TYPE.prompt]: {
    url: "/$workspaceName/prompts/$promptId",
    icon: FileTerminal,
    param: "promptId",
    deleted: "Deleted prompt",
    label: "prompt",
    color: RESOURCE_COLORS[RESOURCE_TYPE.prompt],
  },
  [RESOURCE_TYPE.experiment]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    icon: FlaskConical,
    param: "datasetId",
    deleted: "Deleted experiment",
    label: "experiment",
    color: RESOURCE_COLORS[RESOURCE_TYPE.experiment],
  },
  [RESOURCE_TYPE.optimization]: {
    url: "/$workspaceName/optimizations/$datasetId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "optimization run",
    color: RESOURCE_COLORS[RESOURCE_TYPE.optimization],
  },
  [RESOURCE_TYPE.trial]: {
    url: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Deleted optimization",
    label: "trial",
    color: RESOURCE_COLORS[RESOURCE_TYPE.trial],
  },
  [RESOURCE_TYPE.annotationQueue]: {
    url: "/$workspaceName/annotation-queues/$annotationQueueId",
    icon: UserPen,
    param: "annotationQueueId",
    deleted: "Deleted annotation queue",
    label: "annotation queue",
    color: RESOURCE_COLORS[RESOURCE_TYPE.annotationQueue],
  },
};

type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[]>;
  params?: Record<string, string | number | string[]>;
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
          <div
            className={cn(
              "flex h-6 items-center gap-1.5 rounded-md border border-border px-2 max-w-full",
              deleted && "opacity-50 cursor-default",
            )}
          >
            <props.icon
              className="size-4 shrink-0"
              style={{ color: props.color }}
            />
            <div className="comet-body-s-accented min-w-0 truncate text-muted-slate">
              {text}
            </div>
            {!deleted && (
              <ArrowUpRight className="size-4 shrink-0 text-muted-slate" />
            )}
          </div>
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
