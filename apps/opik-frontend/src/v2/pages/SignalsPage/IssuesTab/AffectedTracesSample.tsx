import React from "react";
import { Coins, Hash, ListTree, Timer } from "lucide-react";
import useTracesList from "@/api/traces/useTracesList";
import { Skeleton } from "@/ui/skeleton";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";

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
    <div className="flex flex-col gap-1.5">
      {traces.map((trace) => (
        <div
          key={trace.id}
          className="comet-body-xs flex items-center gap-4 rounded-md border border-border px-3 py-2"
        >
          <span className="flex items-center gap-1.5 font-mono text-foreground">
            <ListTree className="size-3.5 text-[var(--color-primary)]" />
            {trace.id.slice(0, 4)}...{trace.id.slice(-3)}
          </span>
          <span className="flex items-center gap-1 text-muted-slate">
            <Timer className="size-3.5" />
            {formatDuration(trace.duration)}
          </span>
          <span className="flex items-center gap-1 text-muted-slate">
            <Hash className="size-3.5" />
            {(trace.span_count ?? 0).toLocaleString()}
          </span>
          <span className="flex items-center gap-1 text-muted-slate">
            <Coins className="size-3.5" />
            {formatCost(trace.total_estimated_cost)}
          </span>
          <span className="ml-auto whitespace-nowrap text-light-slate">
            {formatDate(trace.last_updated_at)}
          </span>
        </div>
      ))}
    </div>
  );
};

export default AffectedTracesSample;
