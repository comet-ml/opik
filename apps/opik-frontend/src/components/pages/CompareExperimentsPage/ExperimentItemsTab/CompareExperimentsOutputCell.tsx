import React from "react";
import isFunction from "lodash/isFunction";
import { CellContext } from "@tanstack/react-table";
import JsonView from "react18-json-view";
import { ListTree } from "lucide-react";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/VerticallySplitCellWrapper";

const CompareExperimentsOutputCell: React.FunctionComponent<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { openTrace = true } = (custom ?? {}) as CustomMeta;
  const experimentCompare = context.row.original;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const renderContent = (item: ExperimentItem | undefined) => {
    if (!item) return "-";

    return (
      <>
        <TooltipWrapper content="Click to open original trace">
          <Button
            size="icon-xs"
            variant="outline"
            onClick={() => {
              if (isFunction(openTrace) && item.trace_id) {
                openTrace(item.trace_id);
              }
            }}
            className="absolute right-1 top-1 hidden group-hover:flex"
          >
            <ListTree className="size-4" />
          </Button>
        </TooltipWrapper>
        {isSmall ? (
          <div className="comet-code flex size-full items-center truncate">
            {JSON.stringify(item.output, null, 2)}
          </div>
        ) : (
          <div className="size-full overflow-y-auto whitespace-normal">
            {item.output && (
              <JsonView
                src={item.output}
                theme="github"
                collapseStringsAfterLength={10000}
              />
            )}
          </div>
        )}
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
    ></VerticallySplitCellWrapper>
  );
};

export default CompareExperimentsOutputCell;
