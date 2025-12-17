import { z } from "zod";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";

export const ProjectMetricsWidgetSchema = z.object({
  title: z
    .string({
      required_error: "Widget title is required",
    })
    .min(1, { message: "Widget title is required" }),
  subtitle: z.string().optional(),
  metricType: z
    .string({
      required_error: "Metric type is required",
    })
    .min(1, { message: "Metric type is required" }),
  chartType: z.enum(["line", "bar"]),
  projectId: z.string().optional(),
  traceFilters: FiltersArraySchema.optional(),
  threadFilters: FiltersArraySchema.optional(),
  feedbackScores: z.array(z.string()).optional(),
});

export type ProjectMetricsWidgetFormData = z.infer<
  typeof ProjectMetricsWidgetSchema
>;
