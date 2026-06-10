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
  mode: DiffMode;
  baseValue?: BlueprintValue;
  diffValue?: BlueprintValue;
  basePromptTemplate?: string;
  diffPromptTemplate?: string;
};

const BlueprintDiffRow: React.FC<{ pair: DiffPair }> = ({ pair }) => {
  const {
    key,
    type,
    mode,
    baseValue,
    diffValue,
    basePromptTemplate,
    diffPromptTemplate,
  } = pair;
  const isPrompt = type === BlueprintValueType.PROMPT;

  const baseText = baseValue ? formatBlueprintValue(baseValue) : "";
  const diffText = diffValue ? formatBlueprintValue(diffValue) : "";

  return (
    <TableRow className="border-border bg-background">
      <TableCell className="w-[240px] border-r border-border p-1.5 align-top">
        <KeyCellContent label={key} type={type} mode={mode} />
      </TableCell>
      <TableCell className="p-1.5 align-top">
        {isPrompt ? (
          <PromptCellContent
            base={{
              commit: baseValue?.value ?? "",
              template: basePromptTemplate,
            }}
            diff={{
              commit: diffValue?.value ?? "",
              template: diffPromptTemplate,
            }}
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
