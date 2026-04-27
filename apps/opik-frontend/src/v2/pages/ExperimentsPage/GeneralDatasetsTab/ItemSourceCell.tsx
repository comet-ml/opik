import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Database, ListChecks } from "lucide-react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/shared/ResourceLink/ResourceLink";
import { EVALUATION_METHOD } from "@/types/datasets";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
  getIsDeleted?: (cellData: unknown) => boolean;
};

const ItemSourceCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getIsDeleted,
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined) as string | undefined;
  const id = get(cellData, idKey, undefined) as string | undefined;
  const evaluationMethod = get(cellData, "evaluation_method", undefined) as
    | EVALUATION_METHOD
    | undefined;
  const isDeleted = isFunction(getIsDeleted)
    ? getIsDeleted(cellData)
    : undefined;

  const Icon =
    evaluationMethod === EVALUATION_METHOD.TEST_SUITE ? ListChecks : Database;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="items-center gap-2 py-1.5"
    >
      <Icon className="size-4 shrink-0 text-light-slate" />
      {id ? (
        <ResourceLink
          id={id}
          name={name}
          resource={resource}
          isDeleted={isDeleted}
        />
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default ItemSourceCell;
