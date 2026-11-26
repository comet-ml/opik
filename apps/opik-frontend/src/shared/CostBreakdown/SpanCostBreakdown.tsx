import React from "react";
import {
  CostOrigin,
  CostedSpan,
  ModelPriceResolution,
  billedCostOf,
  describeMismatch,
  describeVersionChange,
  needsBuiltInPrices,
  priceSpan,
  priceTypeOf,
  spanCostOrigin,
  spanModelOf,
  usageRequestOf,
  visibleLines,
} from "@/lib/model-prices";
import { formatExactCost } from "@/lib/money";
import { Skeleton } from "@/ui/skeleton";
import useModelPriceResolve from "@/api/model-prices/useModelPriceResolve";
import useBuiltInPrices from "@/api/model-prices/useBuiltInPrices";
import CostCard, { CostNote } from "./CostCard";
import CostLinesTable from "./CostLinesTable";
import PriceTypeBadge from "./PriceTypeBadge";

const STATUS_LABELS = {
  current: "Still current",
  changed: "Changed since it ran",
  unrecorded: "Not recorded when it ran",
};

export const CostLinesSkeleton: React.FC = () => (
  <div className="flex flex-col gap-2">
    <Skeleton className="h-4 w-2/3" />
    <Skeleton className="h-4 w-full" />
    <Skeleton className="h-4 w-full" />
  </div>
);

type SpanPricesProps = {
  span: CostedSpan;
  origin: CostOrigin;
  resolution: ModelPriceResolution;
};

const SpanPrices: React.FC<SpanPricesProps> = ({
  span,
  origin,
  resolution,
}) => {
  const { data: builtIns } = useBuiltInPrices({
    enabled: needsBuiltInPrices(resolution),
  });
  const breakdown = priceSpan(span, resolution);
  const { status, fromVersions } = breakdown.freshness;
  const isRecorded = origin.kind !== "unrecorded";
  const today = formatExactCost(breakdown.today);
  const versionChange = describeVersionChange(fromVersions, resolution);
  const hasPrice = resolution.cost_source !== "none";

  return (
    <div className="flex flex-col gap-3">
      <div className="comet-body-xs flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-muted-slate">
          {status === "current" && isRecorded
            ? "Prices used when this span ran"
            : "Today's prices"}
        </span>
        <PriceTypeBadge tag={priceTypeOf(resolution, builtIns)} />
        <span className="text-muted-slate">· {STATUS_LABELS[status]}</span>
      </div>
      {origin.kind === "load_failed" && (
        <CostNote>
          Your organization&apos;s prices couldn&apos;t be loaded when this span
          ran, so Opik used the built-in prices.
        </CostNote>
      )}
      {status === "changed" && (
        <CostNote variant="warning">
          {`Prices changed since this span ran${
            versionChange ? ` (${versionChange})` : ""
          }. The rates below are today's: this span would cost ${today} now.`}
        </CostNote>
      )}
      {status === "unrecorded" && (
        <CostNote variant="warning">
          {`Opik didn't record which prices this span used. The rates below are today's: this span would cost ${today} now.`}
        </CostNote>
      )}
      {hasPrice && resolution.lines.length > 0 ? (
        <CostLinesTable lines={visibleLines(breakdown.lines)} />
      ) : (
        <CostNote>No price applies to this model today.</CostNote>
      )}
      {hasPrice && !breakdown.addsUp && (
        <CostNote>{describeMismatch(breakdown.hints)}</CostNote>
      )}
    </div>
  );
};

type SpanCostBreakdownProps = {
  span: CostedSpan;
};

const SpanCostBreakdown: React.FC<SpanCostBreakdownProps> = ({ span }) => {
  const origin = spanCostOrigin(span);
  const model = spanModelOf(span);
  const isEstimated = origin.kind !== "client" && Boolean(model);
  const {
    data: resolution,
    isPending,
    isError,
  } = useModelPriceResolve(
    {
      model,
      provider: span.provider || undefined,
      usage: usageRequestOf(span.usage),
    },
    { enabled: isEstimated },
  );

  const renderBody = () => {
    if (origin.kind === "client") {
      return (
        <CostNote>
          Cost sent by your app; Opik didn&apos;t estimate it.
        </CostNote>
      );
    }
    if (!model) {
      return (
        <CostNote>
          This span has no model, so there are no prices to show.
        </CostNote>
      );
    }
    if (isError) {
      return <CostNote>Couldn&apos;t load the prices for this model.</CostNote>;
    }
    if (isPending || !resolution) return <CostLinesSkeleton />;
    return <SpanPrices span={span} origin={origin} resolution={resolution} />;
  };

  return (
    <CostCard
      total={billedCostOf(span)}
      subtitle={[model, span.provider].filter(Boolean).join(" · ")}
    >
      {renderBody()}
    </CostCard>
  );
};

export default SpanCostBreakdown;
