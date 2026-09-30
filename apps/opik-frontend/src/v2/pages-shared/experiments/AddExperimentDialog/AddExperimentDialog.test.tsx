import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import AddExperimentDialog from "./AddExperimentDialog";

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => string) =>
    selector({ activeWorkspaceName: "test-workspace" }),
  useUserApiKey: () => "test-api-key",
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canCreateExperiments: true } }),
}));

vi.mock("@/hooks/useIsPhone", () => ({
  useIsPhone: () => ({ isPhonePortrait: false }),
}));

vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/api/datasets/useProjectDatasetsList", () => ({
  default: () => ({ data: { content: [], total: 0 }, isLoading: false }),
}));

vi.mock("@/shared/SideDialog/SideDialog", () => ({
  default: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
}));

vi.mock("@/shared/CodeHighlighter/CodeHighlighter", () => ({
  default: ({ data }: { data: string }) => (
    <pre data-testid="experiment-code">{data}</pre>
  ),
}));

vi.mock("@/v2/components/LoadableSelectBox/LoadableSelectBox", () => ({
  default: () => null,
}));

vi.mock("@/shared/InstallOpikSection/InstallOpikSection", () => ({
  default: () => null,
}));

vi.mock("@/v2/pages-shared/onboarding/ApiKeyCard/ApiKeyCard", () => ({
  default: () => null,
}));

vi.mock("@/v2/pages-shared/onboarding/GoogleColabCard/GoogleColabCard", () => ({
  default: () => null,
}));

vi.mock("@/shared/ExplainerDescription/ExplainerDescription", () => ({
  default: () => null,
}));

const toggleEvaluator = (label: string) => {
  const checkbox = screen
    .getByText(label, { selector: "div" })
    .closest("label")
    ?.querySelector("button");
  fireEvent.click(checkbox!);
};

const getCode = () => screen.getByTestId("experiment-code").textContent ?? "";

const renderDialog = () =>
  render(<AddExperimentDialog open setOpen={vi.fn()} projectId="project-1" />);

describe("AddExperimentDialog", () => {
  it("orders evaluators in the snippet by the explanation list, not by click order", () => {
    renderDialog();

    // Hallucination is pre-selected; add a later heuristic, then an earlier one.
    toggleEvaluator("Levenshtein");
    toggleEvaluator("Equals");

    const code = getCode();
    expect(code).toContain(
      "from opik.evaluation.metrics import (Equals, LevenshteinRatio, Hallucination)",
    );
    expect(code).toContain(
      "metrics = [Equals(), LevenshteinRatio(), Hallucination()]",
    );
    expect(code.indexOf('"reference"')).toBeLessThan(code.indexOf('"input"'));
  });

  it("keeps display order when an evaluator is removed and re-added", () => {
    renderDialog();

    toggleEvaluator("Equals");
    toggleEvaluator("Context recall");
    toggleEvaluator("Equals");
    toggleEvaluator("Equals");

    expect(getCode()).toContain(
      "metrics = [Equals(), Hallucination(), ContextRecall()]",
    );
  });
});
