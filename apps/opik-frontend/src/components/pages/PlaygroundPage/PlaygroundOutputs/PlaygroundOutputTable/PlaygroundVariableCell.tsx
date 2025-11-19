import React from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import JsonView from "react18-json-view";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

interface CustomMeta {
  showIndex: boolean;
}

const PlaygroundVariableCell: React.FunctionComponent<
  CellContext<never, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const rowIndex = context.row.index;

  const value = context.getValue() as string;
  const jsonViewTheme = useJsonViewTheme();

  const { showIndex } = (custom ?? {}) as CustomMeta;

  const getContent = () => {
    if (isObject(value)) {
      return (
        <div className="size-full overflow-y-auto overflow-x-hidden whitespace-normal">
          <JsonView
            src={value}
            {...jsonViewTheme}
            collapseStringsAfterLength={10000}
          />
        </div>
      );
    }

    return <div className="size-full overflow-y-auto">{value}</div>;
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="pt-5"
    >
      <div className="size-full pl-1">
        <div className="h-[var(--cell-top-height)] items-center font-semibold">
          {showIndex ? rowIndex + 1 : ""}
        </div>
        <div className="h-[calc(100%-var(--cell-top-height))]">
          {getContent()}
        </div>
      </div>
    </CellWrapper>
  );
};

export default PlaygroundVariableCell;
