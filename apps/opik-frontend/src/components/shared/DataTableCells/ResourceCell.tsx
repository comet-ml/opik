import React from "react";
import get from "lodash/get";
import { CellContext } from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { ArrowUpRight, Database } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { Tag } from "@/components/ui/tag";

export enum RESOURCE_TYPE {
  dataset,
}

const RESOURCE_MAP = {
  [RESOURCE_TYPE.dataset]: {
    url: "/$workspaceName/datasets/$datasetId/items",
    icon: Database,
    param: "datasetId",
  },
};

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
};

const ResourceCell = (context: CellContext<unknown, string>) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined);
  const id = get(cellData, idKey, undefined);

  if (!name || !id) return null;
  const props = RESOURCE_MAP[resource];
  const params: Record<string, string> = {
    workspaceName,
  };

  params[props.param] = id;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Link
        to={props.url}
        params={params}
        onClick={(event) => event.stopPropagation()}
        className="max-w-full"
      >
        <TooltipWrapper content={name}>
          <Tag size="lg" variant="gray" className="flex items-center gap-2">
            <props.icon className="size-4 shrink-0" />
            <div className="truncate">{name}</div>
            <ArrowUpRight className="size-4 shrink-0" />
          </Tag>
        </TooltipWrapper>
      </Link>
    </CellWrapper>
  );
};

export default ResourceCell;
