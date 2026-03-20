// Independent horizontal scrolling on each table half requires overflow-x:auto,
// which creates a scroll container that breaks CSS position:sticky.
// Workaround: split into two DataTables (sticky header + scrollable body)
// with synced horizontal scroll.

import React, { useCallback, useRef } from "react";
import { ColumnDef, ColumnSizingState } from "@tanstack/react-table";
import DataTable from "@/shared/DataTable/DataTable";
import { OnChangeFn, ROW_HEIGHT } from "@/types/shared";

interface ResizeConfig {
  enabled: boolean;
  columnSizing?: ColumnSizingState;
  onColumnResize?: OnChangeFn<ColumnSizingState>;
}

interface StickyScrollTableProps<TData> {
  columns: ColumnDef<TData, unknown>[];
  data: TData[];
  rowHeight: ROW_HEIGHT;
  resizeConfig: ResizeConfig;
  noData: React.ReactNode;
  showLoadingOverlay: boolean;
}

const EMPTY_DATA: never[] = [];

const HeaderWrapper: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => <div className="[&_tbody]:hidden">{children}</div>;

const BodyWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div className="border-b [&_thead]:hidden">{children}</div>
);

const StickyScrollTable = <TData,>({
  columns,
  data,
  rowHeight,
  resizeConfig,
  noData,
  showLoadingOverlay,
}: StickyScrollTableProps<TData>) => {
  const headerScrollRef = useRef<HTMLDivElement>(null);
  const bodyScrollRef = useRef<HTMLDivElement>(null);

  const handleBodyScroll = useCallback(() => {
    if (headerScrollRef.current && bodyScrollRef.current) {
      headerScrollRef.current.scrollLeft = bodyScrollRef.current.scrollLeft;
    }
  }, []);

  const handleHeaderScroll = useCallback(() => {
    if (bodyScrollRef.current && headerScrollRef.current) {
      bodyScrollRef.current.scrollLeft = headerScrollRef.current.scrollLeft;
    }
  }, []);

  return (
    <div>
      <div
        ref={headerScrollRef}
        className="sticky top-0 z-10 overflow-x-auto overflow-y-hidden"
        style={{ scrollbarWidth: "none" }}
        onScroll={handleHeaderScroll}
      >
        <DataTable
          columns={columns}
          data={EMPTY_DATA as TData[]}
          rowHeight={rowHeight}
          resizeConfig={resizeConfig}
          noData={null}
          TableWrapper={HeaderWrapper}
        />
      </div>
      <div
        ref={bodyScrollRef}
        className="overflow-x-auto overflow-y-hidden"
        onScroll={handleBodyScroll}
      >
        <DataTable
          columns={columns}
          data={data}
          rowHeight={rowHeight}
          resizeConfig={resizeConfig}
          noData={noData}
          showLoadingOverlay={showLoadingOverlay}
          TableWrapper={BodyWrapper}
        />
      </div>
    </div>
  );
};

export default StickyScrollTable;
