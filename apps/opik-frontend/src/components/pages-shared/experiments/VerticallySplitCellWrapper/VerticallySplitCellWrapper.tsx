import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";

import {
  Experiment,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import { OnChangeFn, ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { calculateLineHeight } from "@/lib/experiments";
import { traceExist } from "@/lib/traces";
import { CELL_HORIZONTAL_ALIGNMENT_MAP } from "@/constants/shared";
import { cn } from "@/lib/utils";

export type CustomMeta = {
  openTrace?: OnChangeFn<string>;
  experimentsIds: string[];
  experiments?: Experiment[];
  feedbackKey?: string;
  outputKey?: string;
};

export type SplitCellRenderContent = (
  item: ExperimentItem | undefined,
  experimentId: string,
  idx: number,
) => React.ReactNode;
type VerticallySplitCellWrapperProps<TData> = {
  metadata?: ColumnMeta<TData, unknown>;
  tableMetadata?: TableMeta<TData>;
  renderContent: SplitCellRenderContent;
  experimentCompare: ExperimentsCompare;
  rowId: string;
};

const VerticallySplitCellWrapper = <TData,>({
  metadata,
  tableMetadata,
  renderContent,
  experimentCompare,
  rowId,
}: VerticallySplitCellWrapperProps<TData>) => {
  const { custom, type } = metadata ?? {};
  const { experimentsIds } = (custom ?? {}) as CustomMeta;
  const rowHeight = tableMetadata?.rowHeight ?? ROW_HEIGHT.small;

  const items = experimentsIds.map((experimentId) =>
    (experimentCompare.experiment_items || []).find(
      (item) => item.experiment_id === experimentId,
    ),
  );

  if (items.every((item) => !item || !traceExist(item))) {
    return null;
  }

  const lineHeightStyle = calculateLineHeight(rowHeight);

  const highlightSubRow = (virtualRowId: string, highlight: boolean) => {
    if (experimentsIds.length > 1) {
      document
        .querySelectorAll<HTMLElement>(
          `div[data-virtual-row-id="${virtualRowId}"]`,
        )
        .forEach(
          (node) =>
            (node.style.backgroundColor = highlight
              ? "#F1F5F9"
              : "transparent"),
        );
    }
  };

  const renderItem = (item: ExperimentItem | undefined, index: number) => {
    const content = renderContent(item, experimentsIds[index], index);
    const horizontalAlignClass =
      CELL_HORIZONTAL_ALIGNMENT_MAP[type!] ?? "justify-start";

    const virtualRowId = `${rowId}-${index}`;
    return (
      <div
        className={cn(
          "group relative flex min-h-1 w-full px-3 py-1.5",
          horizontalAlignClass,
        )}
        key={item?.id || index}
        style={lineHeightStyle}
        data-virtual-row-id={virtualRowId}
        onMouseEnter={() => highlightSubRow(virtualRowId, true)}
        onMouseLeave={() => highlightSubRow(virtualRowId, false)}
        data-cell-wrapper="true"
      >
        {content}
      </div>
    );
  };

  return (
    <CellWrapper
      metadata={metadata}
      tableMetadata={tableMetadata}
      className="flex-col items-stretch justify-center overflow-hidden p-0"
      dataCellWrapper={false}
    >
      {items.map(renderItem)}
    </CellWrapper>
  );
};

export default VerticallySplitCellWrapper;
