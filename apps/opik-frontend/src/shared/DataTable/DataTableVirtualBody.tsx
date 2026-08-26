import React, { useCallback, useEffect } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import first from "lodash/first";
import last from "lodash/last";

import { TableBody } from "@/ui/table";
import { DataTableBodyProps } from "@/shared/DataTable/DataTableBody";
import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import { observeOwnAxisOffset } from "@/shared/DataTable/virtualizerOptions";
import { cn } from "@/lib/utils";

const ROW_BORDER_SIZE = 1;
const MIN_OVER_SCAN_ROWS = 2;
const OVER_SCAN_HEIGHT_COEFFICIENT = 0.5;
const TYPICAL_VIEWPORT_HEIGHT = 1000;

export const DataTableVirtualBody = <TData,>({
  table,
  renderRow,
  renderNoData,
  showLoadingOverlay = false,
  rowVirtualization,
}: DataTableBodyProps<TData>) => {
  const { scrollContainer, tableOffset } = usePageBodyScrollContainer();
  const { height } = table.options.meta?.rowHeightStyle ?? { height: "44" };

  const enabled = rowVirtualization?.enabled ?? true;
  const rows = table.getRowModel().rows;
  const virtualRowHeight = parseInt(height as string, 10) + ROW_BORDER_SIZE;
  const overscan = Math.max(
    MIN_OVER_SCAN_ROWS,
    Math.floor(
      (TYPICAL_VIEWPORT_HEIGHT / virtualRowHeight) *
        OVER_SCAN_HEIGHT_COEFFICIENT,
    ),
  );

  const getItemKey = useCallback(
    (index: number) => rows[index]?.id ?? index,
    [rows],
  );

  const { getVirtualItems, measure } = useVirtualizer({
    enabled,
    count: rows.length,
    getScrollElement: () => scrollContainer,
    getItemKey,
    estimateSize: () => virtualRowHeight,
    paddingStart: tableOffset,
    overscan,
    observeElementOffset: observeOwnAxisOffset,
  });
  const virtualRows = getVirtualItems();
  const firsRowHeight = (first(virtualRows)?.index ?? 0) * virtualRowHeight;
  const lastRowHeight =
    (rows.length - (last(virtualRows)?.index ?? 0) - 1) * virtualRowHeight;

  useEffect(() => {
    measure();
  }, [virtualRowHeight, measure]);

  const renderVirtualRows = () => {
    return (
      <>
        {!!firsRowHeight && (
          <tr
            style={{
              height: `${firsRowHeight}px`,
            }}
          ></tr>
        )}
        {virtualRows.map((virtualRow) => renderRow(rows[virtualRow.index]))}
        {!!lastRowHeight && (
          <tr
            style={{
              height: `${lastRowHeight}px`,
            }}
          ></tr>
        )}
      </>
    );
  };

  return (
    <TableBody
      className={cn(showLoadingOverlay && "comet-table-body-loading-overlay")}
    >
      {rows.length
        ? enabled
          ? renderVirtualRows()
          : rows.map(renderRow)
        : renderNoData()}
    </TableBody>
  );
};

export default DataTableVirtualBody;
