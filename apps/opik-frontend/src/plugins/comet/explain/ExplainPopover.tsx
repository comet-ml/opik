import { ArrowRight } from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useExplainStore, { useExplainEntry } from "./explainStore";
import { getExplainConfig } from "./registry";

interface Props {
  target: ExplainTarget;
  onContinue: () => void;
}

// Shared underline+arrow link affordance (Figma LinkButton).
const linkClass =
  "inline-flex w-fit items-center gap-0.5 border-b border-foreground leading-4 text-foreground";

const ExplainPopover = ({ target, onContinue }: Props) => {
  const entry = useExplainEntry(target);
  const retry = useExplainStore((s) => s.retry);
  const continueChat = useExplainStore((s) => s.continueChat);
  // Completion/error telemetry fires from the store (once per stream). The
  // popover only handles the user-action "Continue" event below.
  const question = getExplainConfig(target.kind)?.question(target);

  const loading =
    !entry || (entry.phase === "loading" && entry.text.length === 0);

  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-1.5 px-1.5">
        <OllieOwl className="size-4 shrink-0 text-[#F46E41]" />
        <span className="leading-4 text-foreground">Ollie</span>
      </div>
      <div className="my-1 h-px w-full bg-border" />

      <div className="px-2 pt-0.5">
        {loading && (
          <div className="flex items-center gap-2">
            <span className="size-2 shrink-0 animate-ollie-breathe rounded-full bg-[#00D14C]" />
            <span className="leading-4 text-muted-slate">Thinking...</span>
          </div>
        )}

        {!loading && entry.phase === "error" && (
          <div className="flex flex-col gap-2">
            <p className="leading-4 text-destructive">
              {entry.error ?? "Something went wrong."}
            </p>
            <button
              type="button"
              className={linkClass}
              onClick={() => retry(target)}
            >
              Retry
            </button>
          </div>
        )}

        {!loading && entry.phase !== "error" && entry.text.length === 0 && (
          <p className="leading-4 text-muted-slate">
            No explanation available.
          </p>
        )}

        {!loading && entry.phase !== "error" && entry.text.length > 0 && (
          <div className="flex flex-col gap-1">
            <p
              className="whitespace-pre-wrap leading-4 text-foreground"
              role="status"
              aria-live="polite"
            >
              {entry.text}
              {entry.phase === "loading" && (
                <span
                  aria-hidden
                  className="ml-0.5 inline-block h-3.5 w-0.5 animate-caret-blink bg-foreground align-text-bottom"
                />
              )}
            </p>
            {entry.phase === "done" && question && (
              <button
                type="button"
                className={`h-6 ${linkClass}`}
                onClick={() => {
                  trackEvent(OpikEvent.EXPLAIN_CONTINUE_CLICKED, {
                    kind: target.kind,
                  });
                  continueChat(target, question);
                  onContinue();
                }}
              >
                Continue conversation
                <ArrowRight className="size-3" />
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default ExplainPopover;
