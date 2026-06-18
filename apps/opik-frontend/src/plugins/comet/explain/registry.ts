import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

type ExplainKindConfig = {
  /** Button aria-label / popover affordance text. */
  label: string;
  /** The verbatim question seeded into the chat on "Continue with Ollie". */
  question: (target: ExplainTarget) => string;
};

// The three contextual explain actions (OPIK-6425): error, cost, latency — all
// in the Traces table. This registry is the single place a kind's label + seed
// question live, so the button/popover stay generic.
// Total Record (not Partial): adding a new ExplainKind without a config here is
// a compile error at this declaration, instead of a silently missing button.
export const AI_EXPLAIN_REGISTRY: Record<ExplainKind, ExplainKindConfig> = {
  "trace.error": {
    label: "Explain error",
    question: (target) => {
      const type = (target.payload as { exception_type?: string })
        .exception_type;
      return type ? `Explain this error: ${type}` : "Explain this error";
    },
  },
  "trace.cost": {
    label: "Explain cost",
    question: () => "Explain this cost",
  },
  "trace.duration": {
    label: "Explain duration",
    question: () => "Explain this duration",
  },
};

// Returns `| undefined` so callers keep their defensive guards even though the
// registry is currently total.
export const getExplainConfig = (
  kind: ExplainKind,
): ExplainKindConfig | undefined => AI_EXPLAIN_REGISTRY[kind];
