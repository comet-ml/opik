import React from "react";
import { Coins, Hash, ListTree, Timer } from "lucide-react";
import useTracesList from "@/api/traces/useTracesList";
import useTraceThreadPanelsState from "@/v2/pages-shared/traces/useTraceThreadPanelsState";
import { Skeleton } from "@/ui/skeleton";
import { PROVIDERS } from "@/constants/providers";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { cn } from "@/lib/utils";

const SAMPLE_SIZE = 8;

type AffectedTracesSampleProps = {
  projectId: string;
};

// NOTE: the issue API exposes a `traces_query` string but not resolved example
// traces, and that string isn't the Filter[] shape the traces API consumes —
// so until the backend returns example trace ids (or a compatible filter), this
// shows the project's most recent traces as a representative sample.
const AffectedTracesSample: React.FC<AffectedTracesSampleProps> = ({
  projectId,
}) => {
  const { data, isPending } = useTracesList({
    projectId,
    page: 1,
    size: SAMPLE_SIZE,
    sorting: [{ id: "last_updated_at", desc: true }],
    truncate: true,
  });

  const traces = data?.content ?? [];

  // Open the trace in an in-place side panel (deep-linked via ?trace/?span)
  // instead of navigating away to the Logs page.
  const { handleRowClick, activeRowId, panels } = useTraceThreadPanelsState({
    rows: traces,
    type: "trace",
    traceDetailsPanelProps: { projectId },
  });

  if (isPending) {
    return (
      <div className="flex flex-col gap-1.5">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-9 w-full rounded-md" />
        ))}
      </div>
    );
  }

  if (!traces.length) {
    return (
      <p className="comet-body-xs text-muted-slate">No traces to show yet.</p>
    );
  }

  return (
    <>
      <div className="flex flex-col gap-1.5">
        {traces.map((trace) => {
          const provider = trace.providers?.[0];
          const providerConfig = provider ? PROVIDERS[provider] : undefined;
          const ProviderIcon = providerConfig?.icon;

          return (
            <button
              key={trace.id}
              type="button"
              onClick={() => handleRowClick(trace)}
              className={cn(
                "comet-body-xs flex w-full items-center gap-4 rounded-md border border-border px-3 py-2 text-left transition-colors hover:border-primary hover:bg-muted/50",
                activeRowId === trace.id && "border-primary bg-primary-100",
              )}
            >
              <span className="comet-body-xs-accented flex items-center gap-1 text-foreground">
                <ListTree className="size-3 shrink-0 text-[var(--chart-violet)]" />
                {trace.id.slice(0, 4)}...{trace.id.slice(-3)}
              </span>
              <span className="flex items-center gap-1 text-foreground">
                <Timer className="size-3 shrink-0 text-muted-slate" />
                {formatDuration(trace.duration)}
              </span>
              <span className="flex items-center gap-1 text-foreground">
                <Hash className="size-3 shrink-0 text-muted-slate" />
                {(trace.span_count ?? 0).toLocaleString()}
              </span>
              <span className="flex items-center gap-1 text-foreground">
                <Coins className="size-3 shrink-0 text-muted-slate" />
                {formatCost(trace.total_estimated_cost)}
              </span>
              {providerConfig && ProviderIcon && (
                <span className="flex items-center gap-1 text-foreground">
                  <ProviderIcon className="size-3 shrink-0 text-muted-slate" />
                  {providerConfig.label}
                </span>
              )}
              <span className="ml-auto whitespace-nowrap text-muted-slate">
                {formatDate(trace.last_updated_at)}
              </span>
            </button>
          );
        })}
      </div>
      {panels}
    </>
  );
};

export default AffectedTracesSample;
