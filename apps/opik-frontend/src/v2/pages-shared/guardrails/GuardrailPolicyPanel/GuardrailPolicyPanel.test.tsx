import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { TooltipProvider } from "@/ui/tooltip";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailTypes,
} from "@/types/guardrails";
import GuardrailPolicyPanel from "./GuardrailPolicyPanel";

vi.mock("@/api/guardrail-policies/useGuardrailPolicyCreateMutation", () => ({
  default: () => ({ mutateAsync: vi.fn() }),
}));
vi.mock("@/api/guardrail-policies/useGuardrailPolicyUpdateMutation", () => ({
  default: () => ({ mutateAsync: vi.fn() }),
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

const POLICY: GuardrailPolicy = {
  id: "0195b8e0-0000-7000-8000-000000000001",
  name: "support-bot-safety",
  execution_scope: GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
  rules: [{ type: GuardrailTypes.PROMPT_INJECTION, config: {} }],
  created_at: "2026-11-06T10:00:00Z",
  created_by: "admin",
  last_updated_at: "2026-11-06T10:00:00Z",
  last_updated_by: "admin",
};

const submittedName = (buttonName: string) => {
  const button = screen.getByRole("button", {
    name: buttonName,
  }) as HTMLButtonElement;
  return (button.form?.elements.namedItem("name") as HTMLInputElement).value;
};

const renderEditPanel = () => {
  const setOpen = vi.fn();
  render(
    <PermissionsProvider value={DEFAULT_PERMISSIONS}>
      <TooltipProvider>
        <GuardrailPolicyPanel
          mode="edit"
          policy={POLICY}
          open
          setOpen={setOpen}
        />
      </TooltipProvider>
    </PermissionsProvider>,
  );
  return setOpen;
};

const pressEscape = () =>
  fireEvent.keyDown(document, { key: "Escape", code: "Escape" });

describe("GuardrailPolicyPanel", () => {
  it("submits the form of its own panel when another panel is open too", () => {
    render(
      <PermissionsProvider value={DEFAULT_PERMISSIONS}>
        <TooltipProvider>
          <GuardrailPolicyPanel mode="create" open setOpen={vi.fn()} />
          <GuardrailPolicyPanel
            mode="edit"
            policy={POLICY}
            open
            setOpen={vi.fn()}
          />
        </TooltipProvider>
      </PermissionsProvider>,
    );

    expect(submittedName("Create policy")).toBe("");
    expect(submittedName("Save changes")).toBe("support-bot-safety");
  });

  it("closes on Escape while nothing is changed", () => {
    const setOpen = renderEditPanel();

    pressEscape();

    expect(setOpen).toHaveBeenCalledWith(false);
  });

  it("stays open on Escape while there are unsaved changes", () => {
    const setOpen = renderEditPanel();

    fireEvent.change(screen.getByRole("textbox", { name: /description/i }), {
      target: { value: "Typed but not saved" },
    });
    pressEscape();

    expect(setOpen).not.toHaveBeenCalled();
  });
});
