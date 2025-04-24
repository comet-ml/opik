import React from "react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
  getSearch?: (cellData: unknown) => Record<string, string | number>;
  getParams?: (cellData: unknown) => Record<string, string | number>;
};

const ResourceCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getSearch,
    getParams,
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined);
  const id = get(cellData, idKey, undefined);
  const search = isFunction(getSearch) ? getSearch(cellData) : undefined;
  const params = isFunction(getParams) ? getParams(cellData) : undefined;

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
        />
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default ResourceCell;
