import { z } from "zod";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";

const ColumnSortSchema = z.object({
  id: z.string(),
  desc: z.boolean(),
});

export const ExperimentLeaderboardWidgetSchema = z.object({
  overrideDefaults: z.boolean().optional(),
  dataSource: z.nativeEnum(EXPERIMENT_DATA_SOURCE).optional(),
  experimentIds: z.array(z.string()).optional(),
  filters: FiltersArraySchema.optional(),
  selectedColumns: z.array(z.string()).optional(),
  enableRanking: z.boolean().optional(),
  rankingMetric: z.string().optional(),
  columnsOrder: z.array(z.string()).optional(),
  scoresColumnsOrder: z.array(z.string()).optional(),
  metadataColumnsOrder: z.array(z.string()).optional(),
  columnsWidth: z.record(z.string(), z.number()).optional(),
  maxRows: z.number().min(1).max(100).optional(),
  sorting: z.array(ColumnSortSchema).optional(),
});

export type ExperimentLeaderboardWidgetFormData = z.infer<
  typeof ExperimentLeaderboardWidgetSchema
>;
