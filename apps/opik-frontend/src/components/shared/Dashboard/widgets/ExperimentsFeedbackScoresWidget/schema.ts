import { z } from "zod";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { CHART_TYPE } from "@/constants/chart";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";

const GroupSchema = z.object({
  id: z.string(),
  field: z.string(),
  direction: z.nativeEnum(SORT_DIRECTION).or(z.literal("")),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  key: z.string().optional(),
  error: z.string().optional(),
});

export const ExperimentsFeedbackScoresWidgetSchema = z.object({
  title: z
    .string({
      required_error: "Widget title is required",
    })
    .min(1, { message: "Widget title is required" }),
  subtitle: z.string().optional(),
  filters: FiltersArraySchema.optional(),
  groups: z.array(GroupSchema).optional(),
  chartType: z.nativeEnum(CHART_TYPE).optional(),
});

export type ExperimentsFeedbackScoresWidgetFormData = z.infer<
  typeof ExperimentsFeedbackScoresWidgetSchema
>;
