import React from "react";
import sortBy from "lodash/sortBy";
import isFunction from "lodash/isFunction";
import { CellContext } from "@tanstack/react-table";
import JsonView from "react18-json-view";
import { ListTree } from "lucide-react";

import { ExperimentsCompare } from "@/types/datasets";
import { OnChangeFn, ROW_HEIGHT } from "@/types/shared";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { traceExist } from "@/lib/traces";

type CustomMeta = {
  openTrace: OnChangeFn<string>;
};

const CompareExperimentsCell: React.FunctionComponent<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { openTrace = true } = (custom ?? {}) as CustomMeta;
  const experimentId = context.column?.id;
  const experimentCompare = context.row.original;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;

  const item = (experimentCompare.experiment_items || []).find(
    (item) => item.experiment_id === experimentId,
  );

  if (!item || !traceExist(item)) {
    return null;
  }

  const onExpandClick = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (isFunction(openTrace) && item.trace_id) {
      openTrace(item.trace_id);
    }
  };

  const isSmall = rowHeight === ROW_HEIGHT.small;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group flex-col items-stretch gap-2 overflow-y-auto overflow-x-hidden"
    >
      <TooltipWrapper content="Click to open original trace">
        <Button
          size="icon-sm"
          variant="outline"
          onClick={onExpandClick}
          className="absolute right-1 top-1 hidden group-hover:flex"
        >
          <ListTree className="size-4" />
        </Button>
      </TooltipWrapper>
      <div
        className={cn(
          "flex gap-1.5",
          isSmall ? "flex-nowrap overflow-x-auto" : "flex-wrap",
        )}
      >
        {sortBy(item.feedback_scores || [], "name").map((feedbackScore) => {
          return (
            <FeedbackScoreTag
              key={feedbackScore.name}
              label={feedbackScore.name}
              value={feedbackScore.value}
              reason={feedbackScore.reason}
              className="max-w-full"
            />
          );
        })}
      </div>
      {isSmall ? (
        <div className="comet-code flex w-full flex-auto items-center truncate rounded-md border bg-[#FBFCFD] px-2 py-1.5">
          {JSON.stringify(item.output, null, 2)}
        </div>
      ) : (
        <div
          className="w-full flex-auto whitespace-normal rounded-md border bg-[#FBFCFD] p-2"
          onClick={(event) => event.stopPropagation()}
        >
          {item.output && (
            <JsonView
              src={item.output}
              theme="github"
              collapseStringsAfterLength={10000}
            />
          )}
        </div>
      )}
    </CellWrapper>
  );
};

export default CompareExperimentsCell;
