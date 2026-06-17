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
  // Completion/error telemetry fires from the store (once per stream). The
  // popover only handles the user-action "Continue" event below.
  const question = getExplainConfig(target.kind)?.question(target);

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
      {entry.phase === "done" && question && (
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            trackEvent(OpikEvent.EXPLAIN_CONTINUE_CLICKED, {
              kind: target.kind,
            });
            continueChat(target, question);
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
