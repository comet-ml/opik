import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useForm, FormProvider, UseFormReturn } from "react-hook-form";
import CommonMetricRuleDetails from "./CommonMetricRuleDetails";
import {
  EVALUATORS_RULE_SCOPE,
  EVALUATORS_RULE_TYPE,
} from "@/types/automations";
import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "./schema";

// Mock the API hook
vi.mock("@/api/automations/useCommonMetricsQuery", () => ({
  default: vi.fn(() => ({
    data: {
      content: [
        {
          id: "equals",
          name: "Equals",
          description: "Checks if output exactly matches reference",
          score_parameters: [
            {
              name: "output",
              type: "str",
              description: "The output to check",
              required: true,
            },
            {
              name: "reference",
              type: "str",
              description: "The reference to compare",
              required: true,
            },
          ],
          init_parameters: [],
        },
        {
          id: "contains",
          name: "Contains",
          description: "Checks if reference is contained in output",
          score_parameters: [
            {
              name: "output",
              type: "str",
              description: "The output string",
              required: true,
            },
            {
              name: "reference",
              type: "str",
              description: "The reference to find",
              required: false,
            },
          ],
          init_parameters: [
            {
              name: "case_sensitive",
              type: "bool",
              description: "Whether comparison is case-sensitive",
              default_value: "False",
              required: false,
            },
          ],
        },
      ],
    },
    isLoading: false,
    error: null,
  })),
}));

// Mock LLMPromptMessagesVariables
vi.mock(
  "@/components/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables",
  () => ({
    default: ({ variables }: { variables: Record<string, string> }) => (
      <div data-testid="variable-mapping">
        {Object.keys(variables).map((key) => (
          <div key={key} data-testid={`variable-${key}`}>
            {key}
          </div>
        ))}
      </div>
    ),
  }),
);

const createQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

// Wrapper component to provide form context
const TestWrapper = () => {
  const FormWrapper = () => {
    const form = useForm({
      defaultValues: {
        ruleName: "",
        projectIds: ["test-project"],
        samplingRate: 1,
        uiType: UI_EVALUATORS_RULE_TYPE.common_metric,
        scope: EVALUATORS_RULE_SCOPE.trace,
        type: EVALUATORS_RULE_TYPE.python_code,
        enabled: true,
        filters: [],
        pythonCodeDetails: {
          metric: "",
          arguments: {},
        },
        commonMetricDetails: {
          metricId: "",
        },
      },
    });

    return (
      <FormProvider {...form}>
        <CommonMetricRuleDetails form={form as unknown as UseFormReturn<EvaluationRuleFormType>} />
      </FormProvider>
    );
  };

  const queryClient = createQueryClient();
  return (
    <QueryClientProvider client={queryClient}>
      <FormWrapper />
    </QueryClientProvider>
  );
};

describe("CommonMetricRuleDetails", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render metric selector", async () => {
    render(<TestWrapper />);

    await waitFor(() => {
      expect(screen.getByText("Select metric")).toBeInTheDocument();
    });
  });

  it("should show loading state while fetching metrics", async () => {
    const useCommonMetricsQuery = await import(
      "@/api/automations/useCommonMetricsQuery"
    );
    vi.mocked(useCommonMetricsQuery.default).mockReturnValueOnce({
      data: undefined,
      isLoading: true,
      error: null,
      isError: false,
      isPending: true,
      isSuccess: false,
      status: "pending",
    } as unknown as ReturnType<typeof useCommonMetricsQuery.default>);

    render(<TestWrapper />);

    // Should show skeleton while loading
    expect(screen.queryByText("Select metric")).not.toBeInTheDocument();
  });

  it("should show error state when fetching fails", async () => {
    const useCommonMetricsQuery = await import(
      "@/api/automations/useCommonMetricsQuery"
    );
    vi.mocked(useCommonMetricsQuery.default).mockReturnValueOnce({
      data: undefined,
      isLoading: false,
      error: new Error("Failed to fetch"),
      isError: true,
      isPending: false,
      isSuccess: false,
      status: "error",
    } as unknown as ReturnType<typeof useCommonMetricsQuery.default>);

    render(<TestWrapper />);

    await waitFor(() => {
      expect(
        screen.getByText(
          "Failed to load common metrics. Please try again later.",
        ),
      ).toBeInTheDocument();
    });
  });
});
