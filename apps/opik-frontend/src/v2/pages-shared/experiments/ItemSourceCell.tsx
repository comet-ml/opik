import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Database, ListChecks } from "lucide-react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/shared/ResourceLink/ResourceLink";
import { DATASET_TYPE, EVALUATION_METHOD } from "@/types/datasets";

export const ITEM_SOURCE_LABEL = "Item source";

export const resolveItemSourceResource = (
  evaluationMethod: EVALUATION_METHOD | undefined,
  datasetId: string | undefined,
  datasetTypeMap?: Record<string, DATASET_TYPE>,
): RESOURCE_TYPE | undefined => {
  if (evaluationMethod === EVALUATION_METHOD.TEST_SUITE) {
    return RESOURCE_TYPE.testSuite;
  }
  if (evaluationMethod === EVALUATION_METHOD.DATASET) {
    return RESOURCE_TYPE.dataset;
  }
  if (datasetId && datasetTypeMap) {
    const type = datasetTypeMap[datasetId];
    if (type === DATASET_TYPE.TEST_SUITE) return RESOURCE_TYPE.testSuite;
    if (type === DATASET_TYPE.DATASET) return RESOURCE_TYPE.dataset;
  }
  return undefined;
};

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  getIsDeleted?: (cellData: unknown) => boolean;
};

const ItemSourceCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    nameKey = "name",
    idKey = "id",
    getIsDeleted,
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined) as string | undefined;
  const id = get(cellData, idKey, undefined) as string | undefined;
  const evaluationMethod = get(cellData, "evaluation_method", undefined) as
    | EVALUATION_METHOD
    | undefined;
  const datasetTypeMap = context.table.options.meta?.datasetTypeMap;
  const isDeleted = isFunction(getIsDeleted)
    ? getIsDeleted(cellData)
    : undefined;

  const resource = resolveItemSourceResource(
    evaluationMethod,
    id,
    datasetTypeMap,
  );
  const Icon = resource === RESOURCE_TYPE.testSuite ? ListChecks : Database;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center gap-2 py-1.5"
    >
      <Icon className="size-4 shrink-0 text-light-slate" />
      {id && resource ? (
        <ResourceLink
          id={id}
          name={name}
          resource={resource}
          isDeleted={isDeleted}
        />
      ) : id ? (
        <span className="comet-body-s truncate">{name ?? "-"}</span>
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default ItemSourceCell;
