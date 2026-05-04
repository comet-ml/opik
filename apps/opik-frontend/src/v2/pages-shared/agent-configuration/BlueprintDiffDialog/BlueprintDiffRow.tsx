import React from "react";

import { TableCell, TableRow } from "@/ui/table";
import { BlueprintValueType, BlueprintValue } from "@/types/agent-configs";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import {
  DiffMode,
  KeyCellContent,
  PromptCellContent,
  ValueCellContent,
} from "./BlueprintDiffCell";

export type DiffPair = {
  key: string;
  type: BlueprintValueType;
  baseValue?: BlueprintValue;
  diffValue?: BlueprintValue;
  changed?: boolean;
  basePromptTemplate?: string;
  diffPromptTemplate?: string;
};

const getDiffMode = (pair: DiffPair): DiffMode => {
  if (!pair.baseValue && pair.diffValue) return "added";
  if (pair.baseValue && !pair.diffValue) return "removed";
  if (pair.changed === false) return "unchanged";
  return "changed";
};

const BlueprintDiffRow: React.FC<{ pair: DiffPair }> = ({ pair }) => {
  const {
    key,
    type,
    baseValue,
    diffValue,
    basePromptTemplate,
    diffPromptTemplate,
  } = pair;
  const isPrompt = type === BlueprintValueType.PROMPT;
  const mode = getDiffMode(pair);

  const baseText = baseValue ? formatBlueprintValue(baseValue) : "";
  const diffText = diffValue ? formatBlueprintValue(diffValue) : "";

  return (
    <TableRow className="border-border bg-white hover:bg-white">
      <TableCell className="w-[240px] border-r border-border p-1.5 align-top">
        <KeyCellContent label={key} type={type} mode={mode} />
      </TableCell>
      <TableCell className="p-1.5 align-top">
        {isPrompt ? (
          <PromptCellContent
            baseCommit={baseValue?.value ?? ""}
            diffCommit={diffValue?.value ?? ""}
            baseTemplate={basePromptTemplate}
            diffTemplate={diffPromptTemplate}
            mode={mode}
          />
        ) : (
          <ValueCellContent
            baseText={baseText}
            diffText={diffText}
            mode={mode}
          />
        )}
      </TableCell>
    </TableRow>
  );
};

export default BlueprintDiffRow;
