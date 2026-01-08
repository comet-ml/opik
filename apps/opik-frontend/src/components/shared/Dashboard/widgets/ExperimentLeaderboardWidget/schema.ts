import { z } from "zod";

export const ExperimentLeaderboardWidgetSchema = z.object({
  filters: z.array(z.any()).default([]),
  selectedMetrics: z.array(z.string()).default([]),
  primaryMetric: z.string().min(1, "Please select a primary ranking metric"),
  sortOrder: z.enum(["asc", "desc"]).default("desc"),
  showRank: z.boolean().default(true),
  maxRows: z.number().min(1).max(100).default(20),
  displayColumns: z
    .array(z.string())
    .default(["name", "dataset", "duration", "cost", "trace_count"]),
  metadataColumns: z.array(z.string()).default([]),
  columnsOrder: z.array(z.string()).default([]),
});

export type ExperimentLeaderboardWidgetFormData = z.infer<
  typeof ExperimentLeaderboardWidgetSchema
>;

