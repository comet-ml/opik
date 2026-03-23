import { z } from "zod";
import isString from "lodash/isString";
import isEmpty from "lodash/isEmpty";
import { FiltersArraySchema } from "@/shared/FiltersAccordionSection/schema";
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
      return isValidIntegerInRange(
        data.maxRows || "",
        MIN_MAX_EXPERIMENTS,
        MAX_MAX_EXPERIMENTS,
      );
    },
    {
      message: `Maximum rows is required and must be between ${MIN_MAX_EXPERIMENTS} and ${MAX_MAX_EXPERIMENTS}`,
      path: ["maxRows"],
    },
  );

export type ExperimentsLeaderboardWidgetFormData = z.infer<
  typeof ExperimentsLeaderboardWidgetSchema
>;
