import { z } from "zod";
import { ExecutionPolicy, MAX_RUNS_PER_ITEM } from "@/types/test-suites";

export const testSuiteItemFormSchema = z
  .object({
    description: z.string(),
    data: z.string(),
    assertions: z.array(z.object({ value: z.string() })),
    runsPerItem: z.number().min(1).max(MAX_RUNS_PER_ITEM),
    passThreshold: z.number().min(1),
  })
  .refine((data) => data.passThreshold <= data.runsPerItem, {
    message: "Pass threshold cannot exceed runs per item",
    path: ["passThreshold"],
  });

export type TestSuiteItemFormValues = z.infer<typeof testSuiteItemFormSchema>;

export function toFormValues(
  description: string,
  data: Record<string, unknown>,
  assertions: string[],
  policy: ExecutionPolicy,
): TestSuiteItemFormValues {
  return {
    description,
    data: JSON.stringify(data, null, 2),
    assertions: assertions.map((a) => ({ value: a })),
    runsPerItem: policy.runs_per_item,
    passThreshold: policy.pass_threshold,
  };
}

export function fromFormValues(values: TestSuiteItemFormValues): {
  description: string;
  data: Record<string, unknown> | null;
  assertions: string[];
  policy: ExecutionPolicy;
} {
  let data: Record<string, unknown> | null = null;
  try {
    const parsed = JSON.parse(values.data);
    if (typeof parsed === "object" && parsed !== null) {
      data = parsed;
    }
  } catch {
    // invalid JSON — caller decides what to do
  }

  return {
    description: values.description,
    data,
    assertions: values.assertions.map((a) => a.value.trim()).filter(Boolean),
    policy: {
      runs_per_item: values.runsPerItem,
      pass_threshold: values.passThreshold,
    },
  };
}
