import { z } from "zod";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import { CHART_TYPE } from "@/constants/chart";

export const ProjectMetricsWidgetSchema = z.object({
  metricType: z
    .string({
      required_error: "Metric type is required",
    })
    .min(1, { message: "Metric type is required" }),
  chartType: z.nativeEnum(CHART_TYPE),
  projectId: z.string().optional(),
  traceFilters: FiltersArraySchema.optional(),
  threadFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
  feedbackScores: z.array(z.string()).optional(),
  overrideDefaults: z.boolean().optional(),
});

export type ProjectMetricsWidgetFormData = z.infer<
  typeof ProjectMetricsWidgetSchema
>;
