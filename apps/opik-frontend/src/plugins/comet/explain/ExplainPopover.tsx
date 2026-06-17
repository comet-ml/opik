import { useEffect, useRef } from "react";
import { Loader2, RotateCcw } from "lucide-react";
import { Button } from "@/ui/button";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useExplainStore, { useExplainEntry } from "./explainStore";
import { getExplainConfig } from "./registry";

interface Props {
  target: ExplainTarget;
  onContinue: () => void;
}

const ExplainPopover = ({ target, onContinue }: Props) => {
  const entry = useExplainEntry(target);
  const retry = useExplainStore((s) => s.retry);
  const continueChat = useExplainStore((s) => s.continueChat);
  const config = getExplainConfig(target.kind);

  // BI: time-to-first-token + one-shot completion/error reporting.
  const startedAt = useRef(performance.now());
  const firstTokenAt = useRef<number | null>(null);
  const sent = useRef({ done: false, error: false });

  useEffect(() => {
    if (!entry) return;
    if (firstTokenAt.current === null && entry.text.length > 0) {
      firstTokenAt.current = performance.now();
    }
    if (!sent.current.done && entry.phase === "done") {
      sent.current.done = true;
      trackEvent(OpikEvent.EXPLAIN_COMPLETED, {
        kind: target.kind,
        ttft_ms: firstTokenAt.current
          ? Math.round(firstTokenAt.current - startedAt.current)
          : null,
      });
    }
    if (!sent.current.error && entry.phase === "error") {
      sent.current.error = true;
      trackEvent(OpikEvent.EXPLAIN_ERRORED, { kind: target.kind });
    }
  }, [entry, target.kind]);

  if (!entry || (entry.phase === "loading" && entry.text.length === 0)) {
    return (
      <div className="flex items-center gap-2 text-light-slate">
        <Loader2 className="size-4 animate-spin" />
        <span>Ollie is thinking…</span>
      </div>
    );
  }

  if (entry.phase === "error") {
    return (
      <div className="flex flex-col gap-2">
        <p className="text-destructive">
          {entry.error ?? "Something went wrong."}
        </p>
        <Button variant="outline" size="sm" onClick={() => retry(target)}>
          <RotateCcw className="mr-1 size-3.5" /> Retry
        </Button>
      </div>
    );
  }

  if (entry.phase === "done" && entry.text.length === 0) {
    return <p className="text-light-slate">No explanation available.</p>;
  }

  return (
    <div className="flex flex-col gap-3">
      <p className="whitespace-pre-wrap" role="status" aria-live="polite">
        {entry.text}
      </p>
      {entry.phase === "done" && (
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            trackEvent(OpikEvent.EXPLAIN_CONTINUE_CLICKED, {
              kind: target.kind,
            });
            continueChat(target, config?.question(target) ?? "");
            onContinue();
          }}
        >
          Continue with Ollie
        </Button>
      )}
    </div>
  );
};

export default ExplainPopover;
