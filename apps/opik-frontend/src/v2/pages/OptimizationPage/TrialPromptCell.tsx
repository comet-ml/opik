import React, { useMemo, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isObject from "lodash/isObject";
import { GitCompareArrows } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import { Separator } from "@/ui/separator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import PromptDiff from "@/shared/CodeDiff/PromptDiff";
import { extractMessages } from "@/lib/optimization-config";
import { cn } from "@/lib/utils";

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

export const TrialPromptCell = (
  context: CellContext<AggregatedCandidate, unknown>,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentMap, baselineExperiment } = (custom ?? {}) as {
    experimentMap: Map<string, Experiment>;
    baselineExperiment?: Experiment;
  };

  const [diffOpen, setDiffOpen] = useState(false);

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
        <HoverCard
          open={diffOpen}
          onOpenChange={setDiffOpen}
          openDelay={300}
          closeDelay={150}
        >
          {/* Figma (696:44233): a thin separator + the diff button reveal
              together only while hovering the row (rows carry the `group/row`
              class) and stay visible while the popover is open. `hidden` (not
              opacity) so the button reserves no width at rest — the prompt text
              uses the full column until hovered. The button opens the diff on
              hover or click (689:36080) and carries the tooltip. */}
          <div
            className={cn(
              "hidden shrink-0 items-center gap-1 group-hover/row:flex",
              isLarge ? "self-start" : "self-center",
              diffOpen && "flex",
            )}
          >
            <Separator orientation="vertical" className="h-3" />
            <TooltipWrapper content="View diff vs baseline">
              <HoverCardTrigger asChild>
                <button
                  type="button"
                  aria-label="View diff vs baseline"
                  onClick={(e) => {
                    e.stopPropagation();
                    setDiffOpen(true);
                  }}
                  className="text-muted-slate transition-colors hover:text-foreground"
                >
                  <GitCompareArrows className="size-4" />
                </button>
              </HoverCardTrigger>
            </TooltipWrapper>
          </div>
          <HoverCardContent
            align="end"
            className="w-[660px] max-w-[90vw] p-3 shadow-lg"
            onClick={(e) => e.stopPropagation()}
          >
            <h4 className="comet-body-s-accented mb-2 pl-1 text-foreground">
              Diff vs baseline
            </h4>
            <div className="max-h-[420px] overflow-y-auto">
              <PromptDiff
                baseline={baselinePrompt}
                current={currentPrompt}
                variant="panel"
              />
            </div>
          </HoverCardContent>
        </HoverCard>
      )}
    </CellWrapper>
  );
};
