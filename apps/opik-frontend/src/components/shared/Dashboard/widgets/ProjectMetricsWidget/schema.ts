import { z } from "zod";
import { FiltersArraySchema } from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection/schema";

export const ProjectMetricsWidgetSchema = z.object({
  title: z
    .string({
      required_error: "Widget title is required",
    })
    .min(1, { message: "Widget title is required" }),
  subtitle: z.string().optional(),
  metricType: z
    .string({
      required_error: "Metric is required",
    })
    .min(1, { message: "Metric is required" }),
  chartType: z.enum(["line", "bar"]),
  projectId: z
    .string({
      required_error: "Project is required",
    })
    .min(1, { message: "Project is required" }),
  traceFilters: FiltersArraySchema.optional(),
  threadFilters: FiltersArraySchema.optional(),
});

export type ProjectMetricsWidgetFormData = z.infer<
  typeof ProjectMetricsWidgetSchema
>;
