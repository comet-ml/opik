import { z } from "zod";
import { FiltersArraySchema } from "../shared/ProjectWidgetFiltersSection/schema";

export const StatCardWidgetSchema = z.object({
  title: z.string().min(1, "Title is required"),
  subtitle: z.string().optional(),
  source: z.enum(["traces", "spans"]),
  projectId: z.string().min(1, "Project is required"),
  metric: z.string().min(1, "Metric is required"),
  traceFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
});

export type StatCardWidgetFormData = z.infer<typeof StatCardWidgetSchema>;
