import React from "react";
import { CellContext } from "@tanstack/react-table";
import isNumber from "lodash/isNumber";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import VerticallySplitCellWrapper, {
  SplitCellRenderContent,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

const TokensCell = <TData,>(context: CellContext<TData, number>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(value) ? value.toLocaleString() : "-"}
    </CellWrapper>
  );
};

const CompareTokensCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const experimentCompare = context.row.original;

  const renderContent: SplitCellRenderContent = (
    item: ExperimentItem | undefined,
  ) => {
    const tokens = item?.usage?.total_tokens;
    if (!isNumber(tokens)) return "-";
    return tokens.toLocaleString();
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

TokensCell.Compare = CompareTokensCell;

export default TokensCell;
