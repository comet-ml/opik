import React, { useEffect } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import first from "lodash/first";
import last from "lodash/last";

import { TableBody } from "@/components/ui/table";
import { DataTableBodyProps } from "@/components/shared/DataTable/DataTableBody";
import usePageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/usePageBodyScrollContainer";
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
}: DataTableBodyProps<TData>) => {
  const { scrollContainer, tableOffset } = usePageBodyScrollContainer();
  const { height } = table.options.meta?.rowHeightStyle ?? { height: "44" };

  const rows = table.getRowModel().rows ?? [];
  const virtualRowHeight = parseInt(height as string, 10) + ROW_BORDER_SIZE;
  const overscan = Math.max(
    MIN_OVER_SCAN_ROWS,
    Math.floor(
      (TYPICAL_VIEWPORT_HEIGHT / virtualRowHeight) *
        OVER_SCAN_HEIGHT_COEFFICIENT,
    ),
  );

  const { getVirtualItems, measure } = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollContainer,
    getItemKey: (index: number) => rows[index].id ?? index,
    estimateSize: () => virtualRowHeight,
    paddingStart: tableOffset,
    overscan,
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
      {rows?.length ? renderVirtualRows() : renderNoData()}
    </TableBody>
  );
};

export default DataTableVirtualBody;
