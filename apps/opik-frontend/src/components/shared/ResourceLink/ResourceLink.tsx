import React from "react";
import { Link } from "@tanstack/react-router";
import { ArrowUpRight, Database } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { Tag } from "@/components/ui/tag";
import isUndefined from "lodash/isUndefined";

export enum RESOURCE_TYPE {
  dataset,
}

const RESOURCE_MAP = {
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
    deleted: "Dataset deleted",
  },
};

type ResourceLinkProps = {
  name?: string;
  id: string;
  resource: RESOURCE_TYPE;
};

const ResourceLink: React.FunctionComponent<ResourceLinkProps> = ({
  resource,
  name,
  id,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const props = RESOURCE_MAP[resource];
  const params: Record<string, string> = {
    workspaceName,
  };
  params[props.param] = id;

  const deleted = isUndefined(name);
  const text = deleted ? props.deleted : name;

  return (
    <Link
      to={props.url}
      params={params}
      onClick={(event) => event.stopPropagation()}
      className={cn("max-w-full", deleted && "opacity-50 cursor-default")}
      disabled={deleted}
    >
      <TooltipWrapper content={text}>
        <Tag size="lg" variant="gray" className="flex items-center gap-2">
          <props.icon className="size-4 shrink-0" />
          <div className="truncate">{text}</div>
          {!deleted && <ArrowUpRight className="size-4 shrink-0" />}
        </Tag>
      </TooltipWrapper>
    </Link>
  );
};

export default ResourceLink;
