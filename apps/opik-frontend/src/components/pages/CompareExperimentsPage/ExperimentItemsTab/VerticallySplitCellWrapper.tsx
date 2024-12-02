import React from "react";
import { ColumnMeta, TableMeta } from "@tanstack/react-table";

import {
  Experiment,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import { OnChangeFn, ROW_HEIGHT } from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { calculateLineHeight } from "@/components/pages/CompareExperimentsPage/helpers";
import { traceExist } from "@/lib/traces";

export type CustomMeta = {
  openTrace?: OnChangeFn<string>;
  experimentsIds: string[];
  experiments?: Experiment[];
  feedbackKey?: string;
};

type VerticallySplitCellWrapperProps = {
  metadata?: ColumnMeta<unknown, unknown>;
  tableMetadata?: TableMeta<unknown>;
  renderContent: (
    item: ExperimentItem | undefined,
    experimentId: string,
  ) => React.ReactNode;
  experimentCompare: ExperimentsCompare;
  rowId: string;
};

const VerticallySplitCellWrapper: React.FC<VerticallySplitCellWrapperProps> = ({
  metadata,
  tableMetadata,
  renderContent,
  experimentCompare,
  rowId,
}) => {
  const { custom } = metadata ?? {};
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
    const content = renderContent(item, experimentsIds[index]);
    const virtualRowId = `${rowId}-${index}`;
    return (
      <div
        className="group relative flex min-h-1 w-full px-3 py-2"
        key={item?.id || index}
        style={lineHeightStyle}
        data-virtual-row-id={virtualRowId}
        onMouseEnter={() => highlightSubRow(virtualRowId, true)}
        onMouseLeave={() => highlightSubRow(virtualRowId, false)}
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
    >
      {items.map(renderItem)}
    </CellWrapper>
  );
};

export default VerticallySplitCellWrapper;
