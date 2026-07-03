import { ArrowRight } from "lucide-react";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import { ExplainTarget } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { MarkdownPreview } from "@/shared/MarkdownPreview/MarkdownPreview";
import { Button } from "@/ui/button";
import useExplainStore, { useExplainEntry } from "./explainStore";
import { getExplainConfig } from "./registry";

interface Props {
  target: ExplainTarget;
  onContinue: () => void;
}

// Persistent-underline + arrow link affordance (Figma LinkButton). The shared
// <Button variant="tableLink"> gives foreground color + focus ring + disabled
// handling, plus its hover/active primary color (we no longer override that to
// foreground, so the link visibly responds on hover — the border tracks the text
// via hover:border-primary). We keep the underline as a border (not
// text-decoration) because the button is inline-flex and text-decoration won't
// render under the arrow row. <Button> defaults to size="default" (h-10 px-4
// py-2); we strip all of it (h-auto px-0 py-0) so the border-b hugs the text like
// Figma's LinkButton, instead of sitting 8px below it on the default padding.
const linkClass =
  "h-auto rounded-none px-0 py-0 text-xs font-normal leading-4 no-underline border-b border-foreground transition-colors hover:border-primary";

const ExplainPopover = ({ target, onContinue }: Props) => {
  const entry = useExplainEntry(target);
  const retry = useExplainStore((s) => s.retry);
  const continueChat = useExplainStore((s) => s.continueChat);
  // Completion/error telemetry fires from the store (once per stream). The
  // popover only handles the user-action "Continue"/"Retry" events below.
  const question = getExplainConfig(target.kind)?.question(target);

  const phase = entry?.phase;
  // "Thinking…" until the first chunk; "waking" is the same wait, just slow to
  // start (cold pod). Once text streams, show it; on error, show the reason.
  const thinking = !entry || (phase === "loading" && entry.text.length === 0);
  const waking = phase === "waking";
  const isError = phase === "error";
  // Stream has reached a terminal state — drives aria-busy (announce once here,
  // not on every streamed token).
  const isSettled = phase === "done" || phase === "error";
  const hasText =
    (phase === "loading" || phase === "done") && (entry?.text.length ?? 0) > 0;

  return (
    <div className="flex flex-col">
      <div className="flex items-center gap-1.5 px-1.5 pb-1">
        {/* The owl's eye-circles are bottom-heavy in the 13x13 viewBox, so
            items-center leaves extra space above it. Lift it ~1px to optically
            center against the label. (Tune against Figma.) */}
        <OllieOwl className="relative -top-px size-4 shrink-0 text-[var(--color-ollie)]" />
        <span className="leading-4 text-foreground">Ollie</span>
      </div>
      <div className="my-1 h-px w-full bg-border" />

      <div className="px-2 pt-0.5">
        {/* Live region stays mounted for the popover's lifetime so the result
            is announced — a region inserted only once content arrives is often
            missed by screen readers. aria-busy suppresses announcements while the
            answer is still streaming so the reader gets the settled text once,
            not a flood of partial re-reads on every token; it clears on
            done/error, which is when the region is read out. */}
        <div role="status" aria-live="polite" aria-busy={!isSettled}>
          {(thinking || waking) && (
            <div className="flex items-center gap-2">
              <span className="size-2 shrink-0 rounded-full bg-[var(--color-ollie-live)] text-[var(--color-ollie-live)] motion-safe:animate-beacon-pulse" />
              <span className="leading-4 text-muted-slate">
                {waking ? "Ollie is waking up…" : "Thinking..."}
              </span>
            </div>
          )}

          {isError && (
            <p className="leading-4 text-destructive">
              {entry?.error ?? "Something went wrong."}
            </p>
          )}

          {!thinking &&
            !waking &&
            !isError &&
            (entry?.text.length ?? 0) === 0 && (
              <p className="leading-4 text-muted-slate">
                No explanation available.
              </p>
            )}

          {hasText && (
            <div className="relative">
              {/* Reuse the shared markdown renderer (as every other Ollie
                  output surface does) so bold/lists/inline-code aren't shown
                  raw. The streaming caret sits outside ReactMarkdown's subtree.
                  The [&_…] overrides force bold + inline code to the body color
                  (the prose plugin renders them darker) — scoped here so other
                  .comet-markdown surfaces are untouched. */}
              <MarkdownPreview className="!text-xs leading-4 text-foreground [&_b]:text-foreground [&_code]:text-foreground [&_strong]:text-foreground">
                {entry.text}
              </MarkdownPreview>
              {entry.phase === "loading" && (
                <span
                  aria-hidden
                  className="ml-0.5 inline-block h-3.5 w-0.5 animate-caret-blink bg-foreground align-text-bottom"
                />
              )}
            </div>
          )}
        </div>

        {isError && (
          <Button
            type="button"
            variant="tableLink"
            className={`mt-2 ${linkClass}`}
            onClick={() => {
              trackEvent(OpikEvent.EXPLAIN_RETRIED, {
                kind: target.kind,
                code: entry?.code ?? "unknown",
              });
              retry(target);
            }}
          >
            Retry
          </Button>
        )}

        {/* Offered while streaming too: continuing mid-stream carries the
            partial text into the chat and cancels the cell stream. */}
        {hasText && question && (
          <Button
            type="button"
            variant="tableLink"
            className={`mt-3 gap-0.5 ${linkClass}`}
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
          </Button>
        )}
      </div>
    </div>
  );
};

export default ExplainPopover;
