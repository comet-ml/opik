import React from "react";
import { Link } from "@tanstack/react-router";
import {
  ArrowUpRight,
  Database,
  FileTerminal,
  FlaskConical,
  LayoutGrid,
  SparklesIcon,
} from "lucide-react";
import isUndefined from "lodash/isUndefined";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";

export enum RESOURCE_TYPE {
  project,
  dataset,
  prompt,
  experiment,
  optimization,
  trial,
}

const RESOURCE_MAP = {
  [RESOURCE_TYPE.project]: {
    url: "/$workspaceName/projects/$projectId/traces",
    icon: LayoutGrid,
    param: "projectId",
    deleted: "Project deleted",
  },
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Dataset deleted",
  },
  [RESOURCE_TYPE.prompt]: {
    url: "/$workspaceName/prompts/$promptId",
    icon: FileTerminal,
    param: "promptId",
    deleted: "Prompt deleted",
  },
  [RESOURCE_TYPE.experiment]: {
    url: "/$workspaceName/experiments/$datasetId/compare",
    icon: FlaskConical,
    param: "datasetId",
    deleted: "Experiment deleted",
  },
  [RESOURCE_TYPE.optimization]: {
    url: "/$workspaceName/optimizations/$datasetId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Optimization deleted",
  },
  [RESOURCE_TYPE.trial]: {
    url: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
    icon: SparklesIcon,
    param: "datasetId",
    deleted: "Optimization deleted",
  },
};

type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[]>;
  params?: Record<string, string | number | string[]>;
  asTag?: boolean;
};

const ResourceLink: React.FunctionComponent<ResourceLinkProps> = ({
  resource,
  name,
  id,
  search,
  params,
  asTag = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const props = RESOURCE_MAP[resource];
  const linkParams: Record<string, string> = {
    workspaceName,
    ...params,
  };
  linkParams[props.param] = id;

  const deleted = isUndefined(name);
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
        <TooltipWrapper content={text} stopClickPropagation>
          <Tag
            size="lg"
            variant="gray"
            className={cn(
              "flex items-center gap-2",
              deleted && "opacity-50 cursor-default",
            )}
          >
            <props.icon className="size-4 shrink-0" />
            <div className="truncate">{text}</div>
            {!deleted && <ArrowUpRight className="size-4 shrink-0" />}
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
