import React from "react";
import { CellContext } from "@tanstack/react-table";
import JsonView from "react18-json-view";
import { ListTree } from "lucide-react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import isObject from "lodash/isObject";

import { toString } from "@/lib/utils";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { Button } from "@/components/ui/button";

const CompareExperimentsOutputCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { openTrace, outputKey = "" } = (custom ?? {}) as CustomMeta;
  const experimentCompare = context.row.original;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const renderCodeContent = (data: object) => {
    const parsedData = JSON.stringify(data, null, 2);
    return isSmall ? (
      <CellTooltipWrapper content={parsedData}>
        <div className="comet-code flex size-full items-center truncate">
          {parsedData}
        </div>
      </CellTooltipWrapper>
    ) : (
      <div className="size-full overflow-y-auto whitespace-normal">
        {data && (
          <JsonView
            src={data}
            theme="github"
            collapseStringsAfterLength={10000}
          />
        )}
      </div>
    );
  };

  const renderTextContent = (data: string) => {
    return isSmall ? (
      <CellTooltipWrapper content={data}>
        <span className="truncate">{data}</span>
      </CellTooltipWrapper>
    ) : (
      <div className="size-full overflow-y-auto whitespace-pre-wrap break-words">
        {data}
      </div>
    );
  };

  const renderContent = (item: ExperimentItem | undefined) => {
    const data = get(item, ["output", outputKey], undefined);

    if (!data) return "-";

    return (
      <>
        <TooltipWrapper content="Click to open original trace">
          <Button
            size="icon-xs"
            variant="outline"
            onClick={(event) => {
              if (isFunction(openTrace) && item?.trace_id) {
                openTrace(item.trace_id);
              }
              event.stopPropagation();
            }}
            className="absolute right-1 top-1 hidden group-hover:flex"
          >
            <ListTree />
          </Button>
        </TooltipWrapper>
        {isObject(data)
          ? renderCodeContent(data)
          : renderTextContent(toString(data))}
      </>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};
export default CompareExperimentsOutputCell;
