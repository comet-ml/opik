import React from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useForm } from "react-hook-form";

import { DecisionModelSelect } from "./DecisionModelPromptField";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import {
  OpenRouterRegion,
  OpenRouterRegionInfo,
  PROVIDER_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const { useOpenRouterRegion } = vi.hoisted(() => ({
  useOpenRouterRegion: vi.fn(),
}));

vi.mock("@/api/provider-keys/useOpenRouterRegion", () => ({
  default: useOpenRouterRegion,
}));
vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: {
      content: [
        { provider: PROVIDER_TYPE.OPEN_ROUTER },
        { provider: PROVIDER_TYPE.OPEN_AI },
      ],
    },
    isPending: false,
  }),
}));
vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canUpdateAIProviders: true } }),
}));
vi.mock(
  "@/v2/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog",
  () => ({ default: () => null }),
);

const JEV_LATEST = "~typesafe/jev-latest";
const JEV_1_13 = "typesafe/jev-1.13";
const EU_REASON = "Not available in OpenRouter's EU region";

const jevUnavailableIn = (
  unservedRegion: OpenRouterRegion,
  region: OpenRouterRegion = unservedRegion,
): OpenRouterRegionInfo => ({
  region,
  unavailable_decision_models: [JEV_LATEST, JEV_1_13].map((model) => ({
    model,
    region: unservedRegion,
  })),
});

const DecisionModelSelectInForm: React.FC<{ model: string }> = ({ model }) => {
  const form = useForm<EvaluationRuleFormType>({
    defaultValues: {
      llmJudgeDetails: { model },
    } as EvaluationRuleFormType,
  });
  return <DecisionModelSelect form={form} />;
};

const renderSelect = (model: string, regionInfo?: OpenRouterRegionInfo) => {
  useOpenRouterRegion.mockReturnValue({ data: regionInfo });
  render(
    <TooltipProvider>
      <DecisionModelSelectInForm model={model} />
    </TooltipProvider>,
  );
};

const openSelect = () =>
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });

describe("DecisionModelSelect on a regional OpenRouter setup", () => {
  it("disables the Jev models with the reason and keeps GPT 6 Luna", () => {
    renderSelect("gpt-6-luna", jevUnavailableIn("eu"));
    openSelect();

    for (const jev of [JEV_LATEST, JEV_1_13]) {
      const option = screen.getByRole("option", {
        name: (accessibleName) => accessibleName.startsWith(jev),
      });
      expect(option).toHaveAttribute("data-disabled");
      expect(option).toHaveTextContent(EU_REASON);
    }
    expect(
      screen.getByRole("option", { name: "GPT 6 Luna" }),
    ).not.toHaveAttribute("data-disabled");
  });

  it("still shows a saved Jev rule's model, with the reason next to it", () => {
    renderSelect(JEV_LATEST, jevUnavailableIn("us"));

    expect(screen.getByRole("combobox")).toHaveTextContent(JEV_LATEST);
    expect(
      screen.getByText("Not available in OpenRouter's US region"),
    ).toBeInTheDocument();
  });

  it("disables Jev when only the server's decisions URL is regional", () => {
    renderSelect(JEV_LATEST, jevUnavailableIn("eu", "global"));

    expect(screen.getByText(EU_REASON)).toBeInTheDocument();
  });

  it.each<OpenRouterRegionInfo | undefined>([
    { region: "global", unavailable_decision_models: [] },
    { region: "unknown", unavailable_decision_models: [] },
    undefined,
  ])(
    "keeps the Jev models enabled when the server lists none (%j)",
    (regionInfo) => {
      renderSelect(JEV_LATEST, regionInfo);
      openSelect();

      expect(
        screen.getByRole("option", { name: JEV_1_13 }),
      ).not.toHaveAttribute("data-disabled");
      expect(screen.queryByText(/Not available in OpenRouter/)).toBeNull();
    },
  );
});
