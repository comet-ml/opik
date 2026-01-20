import { z } from "zod";
import isString from "lodash/isString";
import isEmpty from "lodash/isEmpty";
import { EXPERIMENT_DATA_SOURCE } from "@/types/dashboard";
import { FiltersArraySchema } from "@/components/shared/FiltersAccordionSection/schema";
import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  isValidIntegerInRange,
} from "@/lib/dashboard/utils";

const ColumnSortSchema = z.object({
  id: z.string(),
  desc: z.boolean(),
});

export const ExperimentsLeaderboardWidgetSchema = z
  .object({
    overrideDefaults: z.boolean(),
    dataSource: z.nativeEnum(EXPERIMENT_DATA_SOURCE),
    experimentIds: z.array(z.string()).optional(),
    filters: FiltersArraySchema.optional(),
    selectedColumns: z.array(z.string()),
    enableRanking: z.boolean(),
    rankingMetric: z.string().optional(),
    rankingDirection: z.boolean().optional(),
    columnsOrder: z.array(z.string()).optional(),
    scoresColumnsOrder: z.array(z.string()).optional(),
    metadataColumnsOrder: z.array(z.string()).optional(),
    columnsWidth: z.record(z.string(), z.number()).optional(),
    maxRows: z.string().optional(),
    sorting: z.array(ColumnSortSchema).optional(),
  })
  .refine(
    (data) => {
      if (data.enableRanking) {
        return !isEmpty(data.rankingMetric) && isString(data.rankingMetric);
      }
      return true;
    },
    {
      message: "Ranking metric is required when ranking is enabled",
      path: ["rankingMetric"],
    },
  )
  .refine(
    (data) => {
      if (
        data.dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP &&
        data.overrideDefaults
      ) {
        return isValidIntegerInRange(
          data.maxRows || "",
          MIN_MAX_EXPERIMENTS,
          MAX_MAX_EXPERIMENTS,
        );
      }
      return true;
    },
    {
      message: `Maximum rows is required and must be between ${MIN_MAX_EXPERIMENTS} and ${MAX_MAX_EXPERIMENTS}`,
      path: ["maxRows"],
    },
  );

export type ExperimentsLeaderboardWidgetFormData = z.infer<
  typeof ExperimentsLeaderboardWidgetSchema
>;
