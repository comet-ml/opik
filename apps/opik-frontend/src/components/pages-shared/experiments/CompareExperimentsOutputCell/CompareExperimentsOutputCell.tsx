import React from "react";
import { CellContext } from "@tanstack/react-table";
import JsonView from "react18-json-view";
import { ListTree } from "lucide-react";
import get from "lodash/get";
import isFunction from "lodash/isFunction";
import isObject from "lodash/isObject";
import isString from "lodash/isString";

import { toString } from "@/lib/utils";
import { traceVisible } from "@/lib/traces";
import { parseImageValue, parseVideoValue } from "@/lib/images";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import ImagesListWrapper from "@/components/pages-shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { Button } from "@/components/ui/button";
import { useJsonViewTheme } from "@/hooks/useJsonViewTheme";

const CompareExperimentsOutputCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { openTrace, outputKey = "" } = (custom ?? {}) as CustomMeta;
  const experimentCompare = context.row.original;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;
  const isBig = rowHeight === ROW_HEIGHT.large;
  const jsonViewTheme = useJsonViewTheme();

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
            {...jsonViewTheme}
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

  const renderMediaContent = (data: string) => {
    if (!isBig) {
      return (
        <CellTooltipWrapper content={data}>
          <span className="truncate">{data}</span>
        </CellTooltipWrapper>
      );
    }

    const video = parseVideoValue(data);
    if (video) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper
            media={[{ ...video, type: ATTACHMENT_TYPE.VIDEO }]}
          />
        </div>
      );
    }

    const image = parseImageValue(data);
    if (image) {
      return (
        <div className="max-h-80 max-w-[320px] overflow-y-auto">
          <ImagesListWrapper
            media={[{ ...image, type: ATTACHMENT_TYPE.IMAGE }]}
          />
        </div>
      );
    }

    return (
      <div className="size-full overflow-y-auto whitespace-pre-wrap break-words">
        {data}
      </div>
    );
  };

  const renderContent = (item: ExperimentItem | undefined) => {
    const data = get(item, ["output", outputKey], undefined);
    const isTraceVisible = item && traceVisible(item);

    if (!data) return "-";

    // Check for media content first if data is a string
    const hasMedia =
      isString(data) &&
      (parseVideoValue(data) !== undefined ||
        parseImageValue(data) !== undefined);

    return (
      <>
        {isTraceVisible && (
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
        )}
        {hasMedia
          ? renderMediaContent(data as string)
          : isObject(data)
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
