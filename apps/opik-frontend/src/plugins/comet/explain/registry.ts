import { ExplainKind, ExplainTarget } from "@/types/assistant-sidebar";

type ExplainKindConfig = {
  /** Button aria-label / popover affordance text. */
  label: string;
  /** The verbatim question seeded into the chat on "Continue with Ollie". */
  question: (target: ExplainTarget) => string;
};

// Only `trace.error` ships at launch (D-D). Cost/Duration are reserved for
// when their data is enrichable; keep this registry the single place a new
// kind is added so the button/popover stay generic.
export const AI_EXPLAIN_REGISTRY: Partial<
  Record<ExplainKind, ExplainKindConfig>
> = {
  "trace.error": {
    label: "Explain error",
    question: (target) => {
      const type = (target.payload as { exception_type?: string })
        .exception_type;
      return type ? `Explain this error: ${type}` : "Explain this error";
    },
  },
};

export const getExplainConfig = (kind: ExplainKind) =>
  AI_EXPLAIN_REGISTRY[kind];
