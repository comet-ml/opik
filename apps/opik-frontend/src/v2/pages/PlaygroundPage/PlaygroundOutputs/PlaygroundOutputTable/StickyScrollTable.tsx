// Independent horizontal scrolling on each table half requires overflow-x:auto,
// which creates a scroll container that breaks CSS position:sticky.
// Workaround: split into two DataTables (sticky header + scrollable body).
// Where scroll-driven animations exist, the browser moves the header from the
// body's scroll offset in the same frame. Copying scrollLeft in a scroll
// handler instead always paints the header at least one frame behind the body,
// because the body scrolls off the main thread.

import React, { useCallback, useRef, useState } from "react";
import isFunction from "lodash/isFunction";
import { ColumnDef, ColumnSizingState } from "@tanstack/react-table";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/shared/DataTable/DataTableVirtualBody";
import StickyScrollTableBodyWrapper from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/StickyScrollTableBodyWrapper";
import { OnChangeFn, ROW_HEIGHT } from "@/types/shared";
import { cn } from "@/lib/utils";

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
  testId: string;
}

const EMPTY_DATA: never[] = [];

const supportsScrollLinkedHeader = () =>
  isFunction(globalThis.CSS?.supports) &&
  CSS.supports("timeline-scope", "--a") &&
  CSS.supports("animation-range", "0px 1px");

const HeaderWrapper: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => <div className="[&_tbody]:hidden">{children}</div>;

const ScrollLinkedHeaderWrapper: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <div className="comet-scroll-linked-follower [&_tbody]:hidden">
    {children}
  </div>
);

const StickyScrollTable = <TData,>({
  columns,
  data,
  rowHeight,
  resizeConfig,
  noData,
  showLoadingOverlay,
  testId,
}: StickyScrollTableProps<TData>) => {
  const [scrollLinked] = useState(supportsScrollLinkedHeader);
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

  const handleHeaderWheel = useCallback((event: React.WheelEvent) => {
    if (bodyScrollRef.current && event.deltaX) {
      bodyScrollRef.current.scrollLeft += event.deltaX;
    }
  }, []);

  return (
    <div className={cn(scrollLinked && "comet-scroll-linked")}>
      <div
        ref={headerScrollRef}
        data-testid={`${testId}-header`}
        className={cn(
          "sticky top-0 z-10",
          scrollLinked
            ? "overflow-clip"
            : "comet-no-scrollbar overflow-x-auto overflow-y-hidden",
        )}
        onScroll={scrollLinked ? undefined : handleHeaderScroll}
        onWheel={scrollLinked ? handleHeaderWheel : undefined}
      >
        <DataTable
          columns={columns}
          data={EMPTY_DATA as TData[]}
          rowHeight={rowHeight}
          resizeConfig={resizeConfig}
          noData={null}
          TableWrapper={
            scrollLinked ? ScrollLinkedHeaderWrapper : HeaderWrapper
          }
        />
      </div>
      <div
        ref={bodyScrollRef}
        data-testid={`${testId}-body`}
        className={cn(
          "overflow-x-auto overflow-y-hidden",
          scrollLinked && "comet-scroll-linked-source",
        )}
        onScroll={scrollLinked ? undefined : handleBodyScroll}
      >
        <DataTable
          columns={columns}
          data={data}
          rowHeight={rowHeight}
          resizeConfig={resizeConfig}
          noData={noData}
          showLoadingOverlay={showLoadingOverlay}
          TableWrapper={StickyScrollTableBodyWrapper}
          TableBody={DataTableVirtualBody}
        />
      </div>
    </div>
  );
};

export default StickyScrollTable;
