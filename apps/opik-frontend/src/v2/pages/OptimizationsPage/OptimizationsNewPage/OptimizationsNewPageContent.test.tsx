import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import OptimizationsNewPageContent from "./OptimizationsNewPageContent";

// ---------------------------------------------------------------------------
// Mock the complex child sections so we can focus on the footer logic.
// ---------------------------------------------------------------------------
vi.mock("./OptimizationsNewPromptSection", () => ({
  default: () => <div data-testid="prompt-section" />,
}));

vi.mock("./OptimizationsNewConfigSidebar", () => ({
  default: () => <div data-testid="config-sidebar" />,
}));

// ---------------------------------------------------------------------------
// Minimal RHF form state returned by the handlers hook.
// ---------------------------------------------------------------------------
const makeFormState = (overrides = {}) => ({
  isSubmitting: false,
  errors: {},
  ...overrides,
});

type MockHandlers = {
  model: string;
  isDatasetError: boolean;
  hasMissingVariables?: boolean;
  missingDatasetVariables?: string[];
  isDatasetLoading?: boolean;
  datasetId?: string;
  isSubmitting?: boolean;
};

// Factory that returns a mock matching useOptimizationsNewFormHandlers' shape.
const makeHandlers = (overrides: MockHandlers) => ({
  form: {
    formState: makeFormState({ isSubmitting: overrides.isSubmitting ?? false }),
    handleSubmit: vi.fn(() => () => Promise.resolve()),
  },
  activeProjectId: "proj-1",
  optimizerType: "gepa",
  metricType: "equals",
  model: overrides.model,
  config: {},
  datasetId: overrides.datasetId ?? "dataset-1",
  datasetSample: [],
  datasetVariables: [],
  missingDatasetVariables: overrides.missingDatasetVariables ?? [],
  isDatasetLoading: overrides.isDatasetLoading ?? false,
  isDatasetError: overrides.isDatasetError,
  handleDatasetChange: vi.fn(),
  handleOptimizerTypeChange: vi.fn(),
  handleOptimizerParamsChange: vi.fn(),
  handleMetricTypeChange: vi.fn(),
  handleMetricParamsChange: vi.fn(),
  handleModelConfigChange: vi.fn(),
  handleModelChange: vi.fn(),
  submitOptimization: vi.fn(),
  handleNameChange: vi.fn(),
  getFirstMetricParamsError: vi.fn(),
});

vi.mock("./useOptimizationsNewFormHandlers", () => ({
  useOptimizationsNewFormHandlers: vi.fn(),
}));

import { useOptimizationsNewFormHandlers } from "./useOptimizationsNewFormHandlers";

const mockUseHandlers = vi.mocked(useOptimizationsNewFormHandlers);

// ---------------------------------------------------------------------------
// Helper: render the component with explicit prop permutations.
// ---------------------------------------------------------------------------
const renderContent = ({
  availableModels = ["openai/gpt-4o"],
  providerKeysReady = true,
  model = "openai/gpt-4o",
  isDatasetError = false,
}: {
  availableModels?: string[];
  providerKeysReady?: boolean;
  model?: string;
  isDatasetError?: boolean;
} = {}) => {
  mockUseHandlers.mockReturnValue(
    makeHandlers({ model, isDatasetError }) as unknown as ReturnType<
      typeof useOptimizationsNewFormHandlers
    >,
  );

  return render(
    <OptimizationsNewPageContent
      onCancel={vi.fn()}
      isPreparingDataset={false}
      availableModels={availableModels}
      providerKeysReady={providerKeysReady}
    />,
  );
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe("OptimizationsNewPageContent — missing provider key (F1)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("does NOT show the provider-key warning when the selected model is in availableModels", () => {
    renderContent({
      model: "openai/gpt-4o",
      availableModels: ["openai/gpt-4o", "anthropic/claude-3-5-sonnet"],
      providerKeysReady: true,
    });

    expect(
      screen.queryByText(/Add or check the API key for this provider/),
    ).not.toBeInTheDocument();
  });

  it("shows the provider-key warning when the model's provider has no configured key", () => {
    // model is set to anthropic, but only openai is in availableModels
    renderContent({
      model: "anthropic/claude-3-5-sonnet",
      availableModels: ["openai/gpt-4o"],
      providerKeysReady: true,
    });

    expect(
      screen.getByText(/Add or check the API key for this provider/),
    ).toBeInTheDocument();
  });

  it("disables the submit button when the provider key is missing", () => {
    renderContent({
      model: "anthropic/claude-3-5-sonnet",
      availableModels: ["openai/gpt-4o"],
      providerKeysReady: true,
    });

    const submitBtn = screen.getByRole("button", { name: /Optimize prompt/i });
    expect(submitBtn).toBeDisabled();
  });

  it("does NOT show the warning while the keys query is still loading (providerKeysReady=false)", () => {
    renderContent({
      model: "anthropic/claude-3-5-sonnet",
      availableModels: [],
      providerKeysReady: false,
    });

    expect(
      screen.queryByText(/Add or check the API key for this provider/),
    ).not.toBeInTheDocument();
  });

  it("does NOT show the warning when model is empty (no model selected)", () => {
    renderContent({
      model: "",
      availableModels: ["openai/gpt-4o"],
      providerKeysReady: true,
    });

    expect(
      screen.queryByText(/Add or check the API key for this provider/),
    ).not.toBeInTheDocument();
  });

  it("shows the warning when a model is selected but availableModels is empty (stale model after key removal)", () => {
    // A dirty `model` can survive a provider-key refetch that empties the
    // provider-backed set. The selected model is no longer backed, so we must
    // still warn + disable submit rather than let a doomed run through.
    renderContent({
      model: "openai/gpt-4o",
      availableModels: [],
      providerKeysReady: true,
    });

    expect(
      screen.getByText(/Add or check the API key for this provider/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Optimize prompt/i }),
    ).toBeDisabled();
  });

  it("the submit button is enabled when the model's provider has a configured key", () => {
    renderContent({
      model: "openai/gpt-4o",
      availableModels: ["openai/gpt-4o"],
      providerKeysReady: true,
    });

    const submitBtn = screen.getByRole("button", { name: /Optimize prompt/i });
    expect(submitBtn).not.toBeDisabled();
  });
});
