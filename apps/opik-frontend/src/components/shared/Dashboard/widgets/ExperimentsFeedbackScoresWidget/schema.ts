import { z } from "zod";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { CHART_TYPE } from "@/constants/chart";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  isValidIntegerInRange,
} from "@/lib/dashboard/utils";

const GroupSchema = z.object({
  id: z.string(),
  field: z.string(),
  direction: z.nativeEnum(SORT_DIRECTION).or(z.literal("")),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  key: z.string().optional(),
  error: z.string().optional(),
});

export const ExperimentsFeedbackScoresWidgetSchema = z
  .object({
    dataSource: z.nativeEnum(EXPERIMENT_DATA_SOURCE).optional(),
    filters: FiltersArraySchema.optional(),
    groups: z.array(GroupSchema).optional(),
    experimentIds: z.array(z.string()).optional(),
    chartType: z.nativeEnum(CHART_TYPE).optional(),
    feedbackScores: z.array(z.string()).optional(),
    overrideDefaults: z.boolean().optional(),
    maxExperimentsCount: z.string().optional(),
  })
  .refine(
    (data) => {
      if (
        data.dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP &&
        data.overrideDefaults
      ) {
        return isValidIntegerInRange(
          data.maxExperimentsCount || "",
          MIN_MAX_EXPERIMENTS,
          MAX_MAX_EXPERIMENTS,
        );
      }
      return true;
    },
    {
      message: `Max experiments to load is required and must be between ${MIN_MAX_EXPERIMENTS} and ${MAX_MAX_EXPERIMENTS}`,
      path: ["maxExperimentsCount"],
    },
  );

export type ExperimentsFeedbackScoresWidgetFormData = z.infer<
  typeof ExperimentsFeedbackScoresWidgetSchema
>;
