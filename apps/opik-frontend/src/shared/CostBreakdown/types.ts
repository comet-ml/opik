import { CostedSpan } from "@/lib/model-prices";
import { Span } from "@/types/traces";

export type CostBreakdownTarget =
  | { kind: "span"; span: CostedSpan }
  | {
      kind: "trace";
      projectId: string;
      traceId: string;
      cost?: number;
      spans?: Span[];
    }
  | { kind: "thread"; projectId: string; threadId: string; cost?: number };

export const costOfTarget = (target: CostBreakdownTarget) =>
  (target.kind === "span" ? target.span.total_estimated_cost : target.cost) ??
  0;
