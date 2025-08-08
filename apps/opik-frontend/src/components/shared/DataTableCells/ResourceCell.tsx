import React from "react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import isNumber from "lodash/isNumber";
import { CellContext } from "@tanstack/react-table";

import { Explainer } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
  getSearch?: (cellData: unknown) => Record<string, string | number>;
  getParams?: (cellData: unknown) => Record<string, string | number>;
  getIsDeleted?: (cellData: unknown) => boolean;
};

const ResourceCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getSearch,
    getParams,
    getIsDeleted,
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined) as string | undefined;
  const id = get(cellData, idKey, undefined) as string | undefined;
  const search = isFunction(getSearch) ? getSearch(cellData) : undefined;
  const params = isFunction(getParams) ? getParams(cellData) : undefined;
  const isDeleted = isFunction(getIsDeleted)
    ? getIsDeleted(cellData)
    : undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-1.5"
    >
      {id ? (
        <ResourceLink
          id={id}
          name={name}
          resource={resource}
          search={search}
          params={params}
          isDeleted={isDeleted}
        />
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

type GroupCustomMeta = {
  valueKey: string;
  labelKey: string;
  countAggregationKey?: string;
  explainer?: Explainer;
} & CustomMeta;

const GroupResourceCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getSearch,
    getParams,
    getIsDeleted,
    countAggregationKey,
    explainer,
  } = (custom ?? {}) as GroupCustomMeta;

  const name = get(cellData, nameKey, undefined) as string | undefined;
  const id = get(cellData, idKey, undefined) as string | undefined;
  const search = isFunction(getSearch) ? getSearch(cellData) : undefined;
  const params = isFunction(getParams) ? getParams(cellData) : undefined;
  const isDeleted = isFunction(getIsDeleted)
    ? getIsDeleted(cellData)
    : undefined;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};
  const data = aggregationMap?.[rowId];
  const count =
    countAggregationKey && data
      ? get(data, countAggregationKey, undefined)
      : undefined;

  const countText = isNumber(count) ? `(${count})` : "";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center gap-1 overflow-hidden"
    >
      {id ? (
        <ResourceLink
          id={id}
          name={name}
          resource={resource}
          search={search}
          params={params}
          isDeleted={isDeleted}
        />
      ) : (
        <div>-</div>
      )}
      <div className="flex shrink-0 items-center">
        {countText}
        {isDeleted && explainer && (
          <ExplainerIcon {...explainer} className="ml-1" />
        )}
      </div>
    </CellWrapper>
  );
};

ResourceCell.Group = GroupResourceCell;

export default ResourceCell;
