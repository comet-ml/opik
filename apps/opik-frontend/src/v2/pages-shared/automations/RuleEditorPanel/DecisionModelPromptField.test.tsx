import React from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useForm } from "react-hook-form";

import { DecisionModelSelect } from "./DecisionModelPromptField";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import { OpenRouterRegion, PROVIDER_TYPE } from "@/types/providers";
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
const EU_REASON = "Not available in OpenRouter's EU region";

const DecisionModelSelectInForm: React.FC<{ model: string }> = ({ model }) => {
  const form = useForm<EvaluationRuleFormType>({
    defaultValues: {
      llmJudgeDetails: { model },
    } as EvaluationRuleFormType,
  });
  return <DecisionModelSelect form={form} />;
};

const renderSelect = (model: string, region?: OpenRouterRegion) => {
  useOpenRouterRegion.mockReturnValue({ data: region });
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
    renderSelect("gpt-6-luna", "eu");
    openSelect();

    for (const jev of [JEV_LATEST, "typesafe/jev-1.13"]) {
      const option = screen.getByRole("option", {
        name: new RegExp(jev.replace(/[.~]/g, "\\$&")),
      });
      expect(option).toHaveAttribute("data-disabled");
      expect(option).toHaveTextContent(EU_REASON);
    }
    expect(
      screen.getByRole("option", { name: "GPT 6 Luna" }),
    ).not.toHaveAttribute("data-disabled");
  });

  it("still shows a saved Jev rule's model, with the reason next to it", () => {
    renderSelect(JEV_LATEST, "us");

    expect(screen.getByRole("combobox")).toHaveTextContent(JEV_LATEST);
    expect(
      screen.getByText("Not available in OpenRouter's US region"),
    ).toBeInTheDocument();
  });

  it.each(["global", "unknown", undefined] as const)(
    "keeps the Jev models enabled for %s",
    (region) => {
      renderSelect(JEV_LATEST, region);
      openSelect();

      expect(
        screen.getByRole("option", { name: "typesafe/jev-1.13" }),
      ).not.toHaveAttribute("data-disabled");
      expect(screen.queryByText(/Not available in OpenRouter/)).toBeNull();
    },
  );
});
