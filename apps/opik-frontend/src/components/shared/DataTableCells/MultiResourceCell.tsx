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
  listKey: string;
  resource: RESOURCE_TYPE;
  getSearch?: (cellData: unknown) => Record<string, string | number>;
};

const MultiResourceCell = (context: CellContext<unknown, string>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    resource,
    nameKey = "name",
    idKey = "id",
    getSearch,
  } = (custom ?? {}) as CustomMeta;

  const list = context.getValue<Record<string, unknown>[]>();

  const renderResourceLink = (cellData: unknown) => {
    const name = get(cellData, nameKey, undefined);
    const id = get(cellData, idKey, undefined);
    const search = isFunction(getSearch) ? getSearch(cellData) : undefined;

    if (!id) return;

    return (
      <ResourceLink id={id} name={name} resource={resource} search={search} />
    );
  };

  const renderSeparator = (idx: number) => {
    if (idx === 0) return;

    return (
      <span key={`resource-separator-${idx}`} className="mr-1">
        ,
      </span>
    );
  };

  const isEmpty = !list?.length;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="overflow-hidden py-1.5"
    >
      {isEmpty
        ? "-"
        : list.map<React.ReactNode>((item, idx) => (
            <React.Fragment key={`${item[idKey]}`}>
              {renderSeparator(idx)}
              {renderResourceLink(item)}
            </React.Fragment>
          ))}
    </CellWrapper>
  );
};

export default MultiResourceCell;
