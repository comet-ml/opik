import { z } from "zod";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export const ProjectStatsCardWidgetSchema = z.object({
  source: z.nativeEnum(TRACE_DATA_TYPE),
  projectId: z.string().optional(),
  metric: z.string().min(1, "Metric is required"),
  traceFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
  overrideDefaults: z.boolean().optional(),
});

export type ProjectStatsCardWidgetFormData = z.infer<
  typeof ProjectStatsCardWidgetSchema
>;
