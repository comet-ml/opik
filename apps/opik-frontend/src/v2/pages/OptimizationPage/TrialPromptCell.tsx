import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isObject from "lodash/isObject";
import { GitCompareArrows } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import PromptDiff from "@/shared/CodeDiff/PromptDiff";
import { extractMessages } from "@/lib/optimization-config";

const getPromptFromExperiment = (experiment: Experiment): unknown => {
  const metadata = experiment.metadata;
  if (!metadata || !isObject(metadata)) return null;
  const config = get(metadata, "configuration");
  if (!config || !isObject(config)) return null;
  const c = config as Record<string, unknown>;
  if ("prompt" in c) return c["prompt"];
  if ("prompt_messages" in c) return c["prompt_messages"];
  return null;
};

const getPromptPreview = (prompt: unknown): string => {
  const messages = extractMessages(prompt);
  if (messages && messages.length > 0) {
    return messages
      .map(
        (m) =>
          `${m.role.charAt(0).toUpperCase() + m.role.slice(1)}\n${m.content}`,
      )
      .join("\n\n");
  }
  if (typeof prompt === "string") return prompt;
  return "";
};

const getPromptSingleLine = (prompt: unknown): string => {
  const messages = extractMessages(prompt);
  if (messages && messages.length > 0) {
    return messages.map((m) => `${m.role}: ${m.content}`).join(" · ");
  }
  if (typeof prompt === "string") return prompt;
  return "";
};

export const TrialPromptCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentMap, baselineExperiment } = (custom ?? {}) as {
    experimentMap: Map<string, Experiment>;
    baselineExperiment?: Experiment;
  };

  const rowHeight =
    (context.table.options.meta as { rowHeight?: ROW_HEIGHT } | undefined)
      ?.rowHeight ?? ROW_HEIGHT.small;
  const isLarge = rowHeight === ROW_HEIGHT.large;

  const experiment = useMemo(() => {
    const id = row.experimentIds?.[0];
    return id ? experimentMap?.get(id) : undefined;
  }, [row.experimentIds, experimentMap]);

  const currentPrompt = useMemo(
    () => (experiment ? getPromptFromExperiment(experiment) : null),
    [experiment],
  );

  const baselinePrompt = useMemo(
    () =>
      baselineExperiment ? getPromptFromExperiment(baselineExperiment) : null,
    [baselineExperiment],
  );

  const isBaseline = experiment?.id === baselineExperiment?.id;

  const showDiff = !isBaseline && !!currentPrompt && !!baselinePrompt;

  const preview = useMemo(
    () =>
      currentPrompt
        ? isLarge
          ? getPromptPreview(currentPrompt)
          : getPromptSingleLine(currentPrompt)
        : "",
    [currentPrompt, isLarge],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {isLarge ? (
        <div className="comet-body-s h-full min-w-0 flex-1 overflow-y-auto whitespace-pre-line break-words">
          {preview || "-"}
        </div>
      ) : (
        <span className="comet-body-s min-w-0 flex-1 truncate">
          {preview || "-"}
        </span>
      )}
      {showDiff && (
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              onClick={(e) => e.stopPropagation()}
              className="shrink-0 self-start text-muted-slate hover:text-foreground"
            >
              <GitCompareArrows className="size-4" />
            </button>
          </PopoverTrigger>
          <PopoverContent
            className="max-h-[400px] w-[500px] overflow-y-auto p-4"
            align="end"
            onClick={(e) => e.stopPropagation()}
          >
            <h4 className="comet-body-s-accented mb-3">
              Prompt diff vs baseline
            </h4>
            <PromptDiff baseline={baselinePrompt} current={currentPrompt} />
          </PopoverContent>
        </Popover>
      )}
    </CellWrapper>
  );
};
