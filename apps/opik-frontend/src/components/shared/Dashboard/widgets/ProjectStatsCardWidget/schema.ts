import { z } from "zod";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export const ProjectStatsCardWidgetSchema = z.object({
  title: z.string().min(1, "Title is required"),
  subtitle: z.string().optional(),
  source: z.nativeEnum(TRACE_DATA_TYPE),
  projectId: z.string().min(1, "Project is required"),
  metric: z.string().min(1, "Metric is required"),
  traceFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
});

export type ProjectStatsCardWidgetFormData = z.infer<
  typeof ProjectStatsCardWidgetSchema
>;
