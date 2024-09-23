import React from "react";
import get from "lodash/get";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

type CustomMeta = {
  nameKey?: string;
  idKey?: string;
  resource: RESOURCE_TYPE;
};

const ResourceCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const cellData = context.row.original;
  const {
    resource,
    nameKey = "name",
    idKey = "id",
  } = (custom ?? {}) as CustomMeta;

  const name = get(cellData, nameKey, undefined);
  const id = get(cellData, idKey, undefined);

  if (!id) return null;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <ResourceLink id={id} name={name} resource={resource} />
    </CellWrapper>
  );
};

export default ResourceCell;
