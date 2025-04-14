import React, { useState, useMemo, useCallback } from "react";
import isObject from "lodash/isObject";
import { CellContext } from "@tanstack/react-table";
import { ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import CellTooltipWrapper from "@/components/shared/DataTableCells/CellTooltipWrapper";
import { prettifyMessage } from "@/lib/traces";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { getTextWidth } from "@/lib/utils";

type CustomMeta = {
  fieldType: "input" | "output";
};

const TruncatedStringContainer: React.FC<{ str: string }> = React.memo(
  ({ str }) => {
    const [truncatePoint, setTruncatePoint] = useState(50);

    const handleResize = useCallback(
      (node: HTMLDivElement) => {
        const width = node.clientWidth;
        const textWidth = getTextWidth([str], {
          font: "14px UbuntuMono-Regular",
        });
        const charWidth = textWidth[0] / str.length;

        const maxChars = Math.floor(width / charWidth + 20);
        const truncatePoint = Math.min(maxChars, str.length);

        setTruncatePoint(truncatePoint);
      },
      [str],
    );

    const { ref: cellRef } = useObserveResizeNode<HTMLDivElement>(handleResize);

    const displayedText = useMemo(() => {
      return (
        str.slice(0, truncatePoint) + (truncatePoint < str.length ? "..." : "")
      );
    }, [str, truncatePoint]);

    return (
      <CellTooltipWrapper content={str}>
        <span className="comet-code flex-1 truncate" ref={cellRef}>
          {displayedText}
        </span>
      </CellTooltipWrapper>
    );
  },
);

TruncatedStringContainer.displayName = "TruncatedStringContainer";

const PrettyCell = React.memo(
  <TData,>(context: CellContext<TData, string | object>) => {
    const { custom } = context.column.columnDef.meta ?? {};
    const { fieldType = "input" } = (custom ?? {}) as CustomMeta;
    const value = context.getValue() as string | object;

    // Memoize the expensive prettifyMessage operation
    const response = useMemo(() => {
      if (!value) {
        return {
          message: "",
          prettified: false,
        };
      }

      return prettifyMessage(value, {
        type: fieldType,
      });
    }, [value, fieldType]);

    const message = useMemo(() => {
      if (isObject(response.message)) {
        return JSON.stringify(value, null, 2);
      }
      return response.message || "";
    }, [response.message, value]);

    const rowHeight =
      context.column.columnDef.meta?.overrideRowHeight ??
      context.table.options.meta?.rowHeight ??
      ROW_HEIGHT.small;

    const isSmall = rowHeight === ROW_HEIGHT.small;

    // Memoize content based on message and isSmall
    const content = useMemo(() => {
      if (isSmall) {
        return <TruncatedStringContainer str={message} />;
      }
      return (
        <div className="comet-code size-full overflow-y-auto whitespace-pre-wrap break-words">
          {message}
        </div>
      );
    }, [isSmall, message]);

    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
      >
        {content}
      </CellWrapper>
    );
  },
);

PrettyCell.displayName = "PrettyCell";

export default PrettyCell;
