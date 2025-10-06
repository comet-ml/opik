import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import JsonView from "react18-json-view";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";
import { processInputData } from "@/lib/images";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { ParsedImageData } from "@/types/attachments";

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

  const images = useMemo(() => {
    try {
      return processInputData({ value }).images;
    } catch (error) {
      return [] as ParsedImageData[];
    }
  }, [value]);

  const getContent = () => {
    if (images.length > 0) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper images={images} />
        </div>
      );
    }

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

    return (
      <div className="size-full min-w-0 overflow-hidden break-words">
        {value}
      </div>
    );
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
