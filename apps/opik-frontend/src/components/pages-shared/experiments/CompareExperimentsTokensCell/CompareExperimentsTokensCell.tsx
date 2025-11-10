import React from "react";
import { CellContext } from "@tanstack/react-table";
import isNumber from "lodash/isNumber";

import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";

export type TokensType = "total" | "prompt" | "completion";

export type CompareExperimentsTokensCellMeta = {
  tokenType: TokensType;
};

const TOKENS_LABEL_MAP: Record<TokensType, string> = {
  total: "total_tokens",
  prompt: "prompt_tokens",
  completion: "completion_tokens",
};

const CompareExperimentsTokensCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { tokenType = "total" } = (custom ??
    {}) as CompareExperimentsTokensCellMeta;

  const renderContent = (item: ExperimentItem | undefined) => {
    const tokenKey = TOKENS_LABEL_MAP[tokenType];
    const tokenValue = item?.usage?.[tokenKey];

    if (!isNumber(tokenValue)) {
      return <span>-</span>;
    }

    return (
      <div className="flex h-4 w-full items-center justify-end">
        <span>{tokenValue.toLocaleString()}</span>
      </div>
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

export default CompareExperimentsTokensCell;
