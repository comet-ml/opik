import React from "react";

import { TableCell, TableRow } from "@/ui/table";
import { BlueprintValueType, BlueprintValue } from "@/types/agent-configs";
import BlueprintTypeIcon from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintTypeIcon";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import {
  type DiffSide,
  DiffCellBox,
  EmptyDiffCell,
  PromptDiffPair,
} from "./BlueprintDiffCell";

export type DiffPair = {
  key: string;
  type: BlueprintValueType;
  description?: string;
  baseValue?: BlueprintValue;
  diffValue?: BlueprintValue;
  changed?: boolean;
  basePromptTemplate?: string;
  diffPromptTemplate?: string;
};

const BlueprintDiffRow: React.FC<{ pair: DiffPair }> = ({ pair }) => {
  const {
    key,
    type,
    description,
    baseValue,
    diffValue,
    changed,
    basePromptTemplate,
    diffPromptTemplate,
  } = pair;
  const isPrompt = type === BlueprintValueType.PROMPT;

  const baseText = baseValue ? formatBlueprintValue(baseValue) : undefined;
  const diffText = diffValue ? formatBlueprintValue(diffValue) : undefined;

  const renderCell = (
    value: BlueprintValue | undefined,
    text: string | undefined,
    side: DiffSide,
  ) => {
    if (!value) return <EmptyDiffCell />;
    return <DiffCellBox text={text!} changed={changed ?? false} side={side} />;
  };

  return (
    <TableRow>
      <TableCell className="w-[240px] px-1 py-3 align-top">
        <TooltipWrapper content={key}>
          <div className="flex min-w-0 items-center gap-2">
            <BlueprintTypeIcon type={type} />
            <span className="comet-body-xs-accented truncate text-foreground">
              {key}
            </span>
          </div>
        </TooltipWrapper>
        {description && (
          <p className="comet-body-xs mt-1 text-light-slate">{description}</p>
        )}
      </TableCell>
      {isPrompt && baseValue?.value && diffValue?.value ? (
        <PromptDiffPair
          baseCommit={baseValue.value}
          diffCommit={diffValue.value}
          baseTemplate={basePromptTemplate}
          diffTemplate={diffPromptTemplate}
        />
      ) : (
        <>
          <TableCell className="w-1/2 py-3 pr-2 align-top">
            {renderCell(baseValue, baseText, "base")}
          </TableCell>
          <TableCell className="w-1/2 py-3 pl-2 align-top">
            {renderCell(diffValue, diffText, "diff")}
          </TableCell>
        </>
      )}
    </TableRow>
  );
};

export default BlueprintDiffRow;
