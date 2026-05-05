import React, { useMemo, useState } from "react";
import { GitCompareArrows } from "lucide-react";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/ui/table";
import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import useAgentConfigById from "@/api/agent-configs/useAgentConfigById";
import Loader from "@/shared/Loader/Loader";
import { Switch } from "@/ui/switch";
import { Label } from "@/ui/label";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import BlueprintDiffRow, { type DiffPair } from "./BlueprintDiffRow";

export type BlueprintVersionInfo = {
  label: string;
  blueprintId: string;
  values?: BlueprintValue[];
  promptTemplates?: Record<string, string>;
};

type BlueprintDiffTableProps = {
  base: BlueprintVersionInfo;
  diff: BlueprintVersionInfo;
  defaultOnlyDiff?: boolean;
};

const BlueprintDiffTable: React.FC<BlueprintDiffTableProps> = ({
  base,
  diff,
  defaultOnlyDiff = false,
}) => {
  const [onlyDiff, setOnlyDiff] = useState(defaultOnlyDiff);

  const { data: fetchedBaseConfig, isPending: baseLoading } =
    useAgentConfigById({
      blueprintId: base.values ? "" : base.blueprintId,
    });
  const { data: fetchedDiffConfig, isPending: diffLoading } =
    useAgentConfigById({
      blueprintId: diff.values ? "" : diff.blueprintId,
    });

  const baseValues = base.values ?? fetchedBaseConfig?.values;
  const diffValues = diff.values ?? fetchedDiffConfig?.values;

  const pairs = useMemo<DiffPair[]>(() => {
    if (!baseValues || !diffValues) return [];

    const baseMap = new Map(baseValues.map((v) => [v.key, v]));
    const diffMap = new Map(diffValues.map((v) => [v.key, v]));
    const allKeys = new Set([...baseMap.keys(), ...diffMap.keys()]);

    return Array.from(allKeys)
      .sort((a, b) => a.localeCompare(b))
      .map((key) => {
        const bv = baseMap.get(key);
        const dv = diffMap.get(key);
        const type = (dv?.type ?? bv?.type) as BlueprintValueType;
        const isPrompt = type === BlueprintValueType.PROMPT;
        const basePromptTemplate = base.promptTemplates?.[key];
        const diffPromptTemplate = diff.promptTemplates?.[key];

        let mode: DiffPair["mode"];
        if (!bv && dv) {
          mode = "added";
        } else if (bv && !dv) {
          mode = "removed";
        } else if (isPrompt) {
          const sameCommit =
            bv?.value === dv?.value &&
            !basePromptTemplate &&
            !diffPromptTemplate;
          mode = sameCommit ? "unchanged" : "changed";
        } else {
          const sameValue =
            (bv ? formatBlueprintValue(bv) : undefined) ===
            (dv ? formatBlueprintValue(dv) : undefined);
          mode = sameValue ? "unchanged" : "changed";
        }

        return {
          key,
          type,
          mode,
          baseValue: bv,
          diffValue: dv,
          basePromptTemplate,
          diffPromptTemplate,
        };
      });
  }, [baseValues, diffValues, base.promptTemplates, diff.promptTemplates]);

  if ((!base.values && baseLoading) || (!diff.values && diffLoading))
    return <Loader />;

  const visiblePairs = onlyDiff
    ? pairs.filter((p) => p.mode !== "unchanged")
    : pairs;

  return (
    <div className="max-h-[60vh] overflow-auto rounded-md border border-border">
      <Table>
        <TableHeader className="sticky top-0 z-10 bg-soft-background [&_tr]:border-b">
          <TableRow className="hover:bg-soft-background">
            <TableHead className="h-8 w-[240px] border-r border-border px-2 py-0">
              <span className="comet-body-xs-accented text-light-slate">
                Key
              </span>
            </TableHead>
            <TableHead className="h-8 px-2 py-0">
              <div className="flex items-center justify-between gap-3">
                <span className="comet-body-xs-accented text-light-slate">
                  {base.label} → {diff.label}
                </span>
                <div className="flex items-center gap-1.5">
                  <Label
                    htmlFor="only-diff"
                    className="comet-body-xs cursor-pointer text-light-slate"
                  >
                    Show differences only
                  </Label>
                  <Switch
                    id="only-diff"
                    checked={onlyDiff}
                    onCheckedChange={setOnlyDiff}
                    size="xs"
                  />
                </div>
              </div>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {visiblePairs.length === 0 ? (
            <TableRow>
              <TableCell colSpan={2} className="bg-background p-0">
                <div className="flex h-60 flex-col items-center justify-center gap-1 text-muted-slate">
                  <GitCompareArrows className="size-4" />
                  <span className="comet-body-s">
                    There are no differences between {base.label} and{" "}
                    {diff.label}
                  </span>
                </div>
              </TableCell>
            </TableRow>
          ) : (
            visiblePairs.map((pair) => (
              <BlueprintDiffRow key={pair.key} pair={pair} />
            ))
          )}
        </TableBody>
      </Table>
    </div>
  );
};

export default BlueprintDiffTable;
