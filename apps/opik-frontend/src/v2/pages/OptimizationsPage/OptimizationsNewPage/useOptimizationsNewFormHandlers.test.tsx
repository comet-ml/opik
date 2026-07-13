import React from "react";
import { describe, it, expect, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { FormProvider, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";
import {
  OptimizationConfigFormType,
  OptimizationConfigSchema,
} from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import { METRIC_TYPE, OPTIMIZER_TYPE } from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

// Only the leaf data/API hooks are mocked (dataset lookups, provider models,
// mutations, routing) so the actual logic under test — the
// `missingDatasetVariables` memo, which gates submission in
// `OptimizationsNewPageContent` (`hasMissingVariables`) — runs for real.
let mockDatasetVariables: string[] = [];

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-1",
  default: (selector: (state: unknown) => unknown) =>
    selector({ activeWorkspaceName: "workspace-1" }),
}));

vi.mock("@/api/datasets/useDatasetById", () => ({
  default: () => ({
    data: { id: "dataset-1", name: "dataset-1" },
    isLoading: false,
    isError: false,
  }),
}));

vi.mock("./useDatasetSamplePreview", () => ({
  default: () => ({
    datasetSample: null,
    datasetVariables: mockDatasetVariables,
    areColumnsLoading: false,
  }),
}));

vi.mock("./formHandlers/useOptimizerFormHandlers", () => ({
  useOptimizerFormHandlers: () => ({
    handleOptimizerTypeChange: vi.fn(),
    handleOptimizerParamsChange: vi.fn(),
  }),
}));

vi.mock("./formHandlers/useMetricFormHandlers", () => ({
  useMetricFormHandlers: () => ({
    handleMetricTypeChange: vi.fn(),
    handleMetricParamsChange: vi.fn(),
    getFirstMetricParamsError: vi.fn(() => null),
  }),
}));

vi.mock("./formHandlers/useModelFormHandlers", () => ({
  useModelFormHandlers: () => ({
    handleModelChange: vi.fn(),
    handleModelConfigChange: vi.fn(),
  }),
}));

vi.mock("./formHandlers/useSubmitOptimization", () => ({
  useSubmitOptimization: () => ({ submitOptimization: vi.fn() }),
}));

const buildDefaultValues = (
  code: string,
  argumentsMap?: Record<string, string>,
): OptimizationConfigFormType => ({
  name: "",
  datasetId: "dataset-1",
  optimizerType: OPTIMIZER_TYPE.GEPA,
  optimizerParams: {},
  messages: [
    {
      id: "1",
      role: LLM_MESSAGE_ROLE.user,
      content: "Classify: {{text}}",
    },
  ],
  modelName: "anthropic/claude-haiku",
  modelConfig: {},
  metricType: METRIC_TYPE.CODE,
  metricParams: { code, ...(argumentsMap ? { arguments: argumentsMap } : {}) },
});

const renderFormHandlers = (defaultValues: OptimizationConfigFormType) => {
  const Wrapper = ({ children }: { children: React.ReactNode }) => {
    const form = useForm<OptimizationConfigFormType>({
      resolver: zodResolver(OptimizationConfigSchema),
      defaultValues,
      mode: "onSubmit",
      reValidateMode: "onChange",
    });
    return <FormProvider {...form}>{children}</FormProvider>;
  };
  return renderHook(() => useOptimizationsNewFormHandlers(), {
    wrapper: Wrapper,
  });
};

describe("useOptimizationsNewFormHandlers — missingDatasetVariables (code metric)", () => {
  it("flags a kwargs-referenced column absent from the item source, blocking submit", () => {
    mockDatasetVariables = ["text", "label"];
    const code = `
def score(self, output, **kwargs):
    return kwargs.get("nonexistent_column")
`;
    const { result } = renderFormHandlers(buildDefaultValues(code));

    expect(result.current.missingDatasetVariables).toEqual([
      "nonexistent_column",
    ]);
    // This is exactly the flag `OptimizationsNewPageContent` uses to disable
    // the submit button (`hasMissingVariables = missingDatasetVariables.length > 0`).
    expect(result.current.missingDatasetVariables.length > 0).toBe(true);
  });

  it("flags an `arguments` map entry pointing at a column absent from the item source", () => {
    mockDatasetVariables = ["text", "label"];
    const code = `
def score(self, output, reference):
    return reference
`;
    const { result } = renderFormHandlers(
      buildDefaultValues(code, { reference: "unmapped_column" }),
    );

    expect(result.current.missingDatasetVariables).toEqual(["unmapped_column"]);
  });

  it("does not flag a kwargs-referenced column that matches the item source", () => {
    mockDatasetVariables = ["text", "label"];
    const code = `
def score(self, output, **kwargs):
    return kwargs.get("label")
`;
    const { result } = renderFormHandlers(buildDefaultValues(code));

    expect(result.current.missingDatasetVariables).toEqual([]);
  });

  it("does not flag an unmapped param with no `arguments` entry (falls back to same-named column backend-side)", () => {
    mockDatasetVariables = ["text", "label"];
    const code = `
def score(self, output, **kwargs):
    return kwargs.get("label")
`;
    // Empty mapping: nothing explicit to check, only the kwargs scan applies.
    const { result } = renderFormHandlers(buildDefaultValues(code, {}));

    expect(result.current.missingDatasetVariables).toEqual([]);
  });

  it("skips the check entirely while the item source's columns are still unknown", () => {
    mockDatasetVariables = [];
    const code = `kwargs.get("nonexistent_column")`;
    const { result } = renderFormHandlers(buildDefaultValues(code));

    expect(result.current.missingDatasetVariables).toEqual([]);
  });
});
