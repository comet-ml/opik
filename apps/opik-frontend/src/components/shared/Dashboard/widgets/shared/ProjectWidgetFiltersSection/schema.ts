import { z } from "zod";
import { COLUMN_TYPE } from "@/types/shared";

const FilterSchema = z.object({
  id: z.string(),
  field: z.string(),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  operator: z.string(),
  value: z.union([z.string(), z.number()]),
  key: z.string().optional(),
  error: z.string().optional(),
});

export const FiltersArraySchema = z
  .array(FilterSchema)
  .superRefine((filters, ctx) => {
    filters.forEach((filter, index) => {
      if (!filter.field || filter.field.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Field is required",
          path: [index, "field"],
        });
      }

      if (!filter.operator || filter.operator.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Operator is required",
          path: [index, "operator"],
        });
      }

      if (
        filter.operator &&
        filter.operator !== "is_empty" &&
        filter.operator !== "is_not_empty"
      ) {
        const valueString = String(filter.value || "").trim();
        if (valueString.length === 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: "Value is required for this operator",
            path: [index, "value"],
          });
        }
      }

      if (
        (filter.type === COLUMN_TYPE.dictionary ||
          filter.type === COLUMN_TYPE.numberDictionary) &&
        (!filter.key || filter.key.trim().length === 0)
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: "Key is required for dictionary fields",
          path: [index, "key"],
        });
      }

      if (filter.error) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: filter.error,
          path: [index, "value"],
        });
      }
    });
  });
