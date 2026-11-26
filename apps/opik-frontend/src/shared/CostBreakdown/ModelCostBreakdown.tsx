import React, { useMemo, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import {
  ModelCostGroup,
  costsMatch,
  describeMismatch,
  describeVersionChange,
  groupSpanCosts,
  needsBuiltInPrices,
  priceGroup,
  priceTypeOf,
  usageForPrices,
  visibleLines,
} from "@/lib/model-prices";
import { formatExactCost } from "@/lib/money";
import { formatTokens } from "./format";
import { Span } from "@/types/traces";
import useModelPriceResolve from "@/api/model-prices/useModelPriceResolve";
import useBuiltInPrices from "@/api/model-prices/useBuiltInPrices";
import CostCard, { CostNote } from "./CostCard";
import CostLinesTable from "./CostLinesTable";
import PriceTypeBadge from "./PriceTypeBadge";
import { CostLinesSkeleton } from "./SpanCostBreakdown";

const ROW_GRID =
  "grid grid-cols-[minmax(0,1fr)_2.5rem_4rem_4rem_5.5rem] items-start gap-x-2";

const plural = (count: number, word: string) =>
  `${count.toLocaleString("en-US")} ${word}${count === 1 ? "" : "s"}`;

type ModelGroupDetailsProps = {
  group: ModelCostGroup;
};

const ModelGroupDetails: React.FC<ModelGroupDetailsProps> = ({ group }) => {
  const {
    data: resolution,
    isPending,
    isError,
  } = useModelPriceResolve(
    {
      model: group.model,
      provider: group.provider || undefined,
      usage: usageForPrices(group.spans),
    },
    { enabled: Boolean(group.model) },
  );
  const { data: builtIns } = useBuiltInPrices({
    enabled: Boolean(resolution && needsBuiltInPrices(resolution)),
  });

  if (!group.model) {
    return (
      <CostNote>
        These spans have no model, so there are no prices to show.
      </CostNote>
    );
  }
  if (isError) {
    return <CostNote>Couldn&apos;t load the prices for this model.</CostNote>;
  }
  if (isPending || !resolution) return <CostLinesSkeleton />;

  const breakdown = priceGroup(group, resolution);
  const spanCount = group.spans.length;
  const changedScope =
    breakdown.changedSpans === spanCount
      ? spanCount === 1
        ? "this span"
        : "these spans"
      : `${breakdown.changedSpans} of these ${spanCount} spans`;
  const versionChange = describeVersionChange(
    breakdown.fromVersions,
    resolution,
  );
  const hasPrice = resolution.cost_source !== "none";

  return (
    <div className="flex flex-col gap-2">
      <div className="comet-body-xs flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-muted-slate">
          {breakdown.changedSpans || breakdown.unrecordedSpans
            ? "Today's prices"
            : "Prices used when these spans ran"}
        </span>
        <PriceTypeBadge tag={priceTypeOf(resolution, builtIns)} />
        <span className="text-muted-slate">
          ·{" "}
          {breakdown.changedSpans
            ? "Changed since they ran"
            : breakdown.unrecordedSpans
              ? "Not recorded when they ran"
              : "Still current"}
        </span>
      </div>
      {breakdown.changedSpans > 0 && (
        <CostNote variant="warning">
          {`Prices changed since ${changedScope} ran${
            versionChange ? ` (${versionChange})` : ""
          }. The rates below are today's: ${
            spanCount === 1 ? "it" : "these spans"
          } would cost ${formatExactCost(breakdown.today)} now.`}
        </CostNote>
      )}
      {breakdown.unrecordedSpans > 0 && !breakdown.changedSpans && (
        <CostNote variant="warning">
          {`Opik didn't record which prices ${
            breakdown.unrecordedSpans === spanCount
              ? spanCount === 1
                ? "this span"
                : "these spans"
              : `${breakdown.unrecordedSpans} of these ${spanCount} spans`
          } used. The rates below are today's: ${
            spanCount === 1 ? "it" : "these spans"
          } would cost ${formatExactCost(breakdown.today)} now.`}
        </CostNote>
      )}
      {hasPrice && resolution.lines.length > 0 ? (
        <CostLinesTable lines={visibleLines(breakdown.lines)} />
      ) : (
        <CostNote>No price applies to this model today.</CostNote>
      )}
      {hasPrice && breakdown.unexplainedSpans > 0 && (
        <CostNote>{describeMismatch(breakdown.hints)}</CostNote>
      )}
    </div>
  );
};

type ModelGroupRowProps = {
  group: ModelCostGroup;
};

const ModelGroupRow: React.FC<ModelGroupRowProps> = ({ group }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const ExpandIcon = isExpanded ? ChevronDown : ChevronRight;

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        className={`${ROW_GRID} comet-body-xs rounded-sm text-left tabular-nums hover:bg-muted`}
        onClick={() => setIsExpanded((expanded) => !expanded)}
        aria-expanded={isExpanded}
      >
        <span className="flex min-w-0 items-start gap-1">
          <ExpandIcon className="mt-0.5 size-3.5 shrink-0 text-muted-slate" />
          <span className="flex min-w-0 flex-col">
            <span className="truncate">{group.model || "Unknown model"}</span>
            {group.provider && (
              <span className="truncate text-muted-slate">
                {group.provider}
              </span>
            )}
          </span>
        </span>
        <span className="text-right">{group.spans.length}</span>
        <span className="text-right">{formatTokens(group.inputTokens)}</span>
        <span className="text-right">{formatTokens(group.outputTokens)}</span>
        <span className="text-right">{formatExactCost(group.cost)}</span>
      </button>
      {isExpanded && (
        <div className="ml-4 border-l pl-3">
          <ModelGroupDetails group={group} />
        </div>
      )}
    </div>
  );
};

type ModelCostBreakdownProps = {
  spans: Span[];
  total: number;
  entity: "trace" | "thread";
  isPending: boolean;
  isError: boolean;
  scopeNote?: string;
};

const ModelCostBreakdown: React.FC<ModelCostBreakdownProps> = ({
  spans,
  total,
  entity,
  isPending,
  isError,
  scopeNote,
}) => {
  const {
    groups,
    sentByApp,
    total: spansTotal,
  } = useMemo(() => groupSpanCosts(spans), [spans]);
  const spanCount =
    groups.reduce((count, group) => count + group.spans.length, 0) +
    sentByApp.spans;

  const renderBody = () => {
    if (isError) {
      return (
        <CostNote>Couldn&apos;t load the spans of this {entity}.</CostNote>
      );
    }
    if (isPending) return <CostLinesSkeleton />;
    if (!spanCount) {
      return <CostNote>No spans with a cost were found.</CostNote>;
    }
    return (
      <div className="flex flex-col gap-2">
        <div
          className={`${ROW_GRID} comet-body-xs text-muted-slate`}
          aria-hidden
        >
          <span>Model</span>
          <span className="text-right">Spans</span>
          <span className="text-right">Input tok</span>
          <span className="text-right">Output tok</span>
          <span className="text-right">Cost</span>
        </div>
        {groups.map((group) => (
          <ModelGroupRow key={group.key} group={group} />
        ))}
        {sentByApp.spans > 0 && (
          <div className={`${ROW_GRID} comet-body-xs tabular-nums`}>
            <span className="truncate pl-[18px]">Sent by your app</span>
            <span className="text-right">{sentByApp.spans}</span>
            <span className="text-right text-muted-slate">—</span>
            <span className="text-right text-muted-slate">—</span>
            <span className="text-right">
              {formatExactCost(sentByApp.cost)}
            </span>
          </div>
        )}
        {sentByApp.spans > 0 && (
          <span className="comet-body-xs text-muted-slate">
            Your app sent these costs; Opik didn&apos;t estimate them.
          </span>
        )}
        {scopeNote && <CostNote>{scopeNote}</CostNote>}
        {!scopeNote && !costsMatch(spansTotal, total) && (
          <CostNote>
            {`The spans found add up to ${formatExactCost(
              spansTotal,
            )}, not the ${entity}'s ${formatExactCost(total)}.`}
          </CostNote>
        )}
      </div>
    );
  };

  return (
    <CostCard
      total={total}
      subtitle={
        isPending || isError
          ? undefined
          : `${plural(spanCount, "span")} with a cost · ${plural(
              groups.length,
              "model",
            )}`
      }
    >
      {renderBody()}
    </CostCard>
  );
};

export default ModelCostBreakdown;
