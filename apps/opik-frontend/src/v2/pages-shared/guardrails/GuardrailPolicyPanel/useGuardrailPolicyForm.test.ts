import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailTypes,
} from "@/types/guardrails";
import useGuardrailPolicyForm, {
  GuardrailPolicyPanelMode,
  NAME_TAKEN_MESSAGE,
} from "./useGuardrailPolicyForm";
import { NO_RULE_MESSAGE } from "./schema";

const { createMutate, updateMutate, toast } = vi.hoisted(() => ({
  createMutate: vi.fn(),
  updateMutate: vi.fn(),
  toast: vi.fn(),
}));

vi.mock("@/api/guardrail-policies/useGuardrailPolicyCreateMutation", () => ({
  default: () => ({ mutate: createMutate, isPending: false }),
}));
vi.mock("@/api/guardrail-policies/useGuardrailPolicyUpdateMutation", () => ({
  default: () => ({ mutate: updateMutate, isPending: false }),
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast }) }));

const createPolicy = (
  overrides: Partial<GuardrailPolicy> = {},
): GuardrailPolicy => ({
  id: "policy-1",
  name: "support-bot",
  description: "Keeps the bot safe",
  execution_scope: GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
  rules: [{ type: GuardrailTypes.PROMPT_INJECTION, config: {} }],
  created_at: "2026-10-28T00:00:00Z",
  created_by: "admin",
  last_updated_at: "2026-10-28T00:00:00Z",
  last_updated_by: "admin",
  ...overrides,
});

const renderForm = (
  mode: GuardrailPolicyPanelMode,
  policy?: GuardrailPolicy,
  takenNames?: string[],
) => {
  const setOpen = vi.fn();
  const rendered = renderHook(
    ({ open }) =>
      useGuardrailPolicyForm({ mode, policy, takenNames, open, setOpen }),
    { initialProps: { open: true } },
  );
  return { ...rendered, setOpen };
};

const submit = async (
  result: ReturnType<typeof renderForm>["result"],
): Promise<void> => {
  await act(() => result.current.submitHandler());
};

describe("useGuardrailPolicyForm", () => {
  beforeEach(() => {
    createMutate.mockReset();
    updateMutate.mockReset();
    toast.mockReset();
  });

  it("creates a policy with the trimmed name and the enabled rules", async () => {
    const { result } = renderForm("create");

    act(() => {
      result.current.form.setValue("name", "  support-bot  ");
      result.current.form.setValue(`rules.${GuardrailTypes.PII}.enabled`, true);
    });
    await submit(result);

    expect(updateMutate).not.toHaveBeenCalled();
    expect(createMutate).toHaveBeenCalledWith(
      {
        policy: {
          name: "support-bot",
          execution_scope: GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
          rules: [
            {
              type: GuardrailTypes.PII,
              config: { entities: ["CREDIT_CARD", "PHONE_NUMBER"] },
            },
          ],
        },
      },
      expect.anything(),
    );
  });

  it("updates an edited policy by its id", async () => {
    const { result } = renderForm("edit", createPolicy());

    expect(result.current.isDirty).toBe(false);
    act(() => {
      result.current.form.setValue("description", "Updated", {
        shouldDirty: true,
      });
    });
    expect(result.current.isDirty).toBe(true);
    await submit(result);

    expect(createMutate).not.toHaveBeenCalled();
    expect(updateMutate).toHaveBeenCalledWith(
      {
        policyId: "policy-1",
        policy: expect.objectContaining({ description: "Updated" }),
      },
      expect.anything(),
    );
  });

  it("shows a taken name on the name field instead of a toast", async () => {
    createMutate.mockImplementation((_, { onError }) =>
      onError({ response: { status: 409 } }),
    );
    const { result } = renderForm("duplicate", createPolicy());

    await submit(result);

    expect(result.current.form.getFieldState("name").error?.message).toBe(
      NAME_TAKEN_MESSAGE,
    );
    expect(toast).not.toHaveBeenCalled();
  });

  it("does not send a policy with no rule switched on", async () => {
    const { result } = renderForm("create");

    act(() => {
      result.current.form.setValue("name", "support-bot");
    });
    await submit(result);

    expect(createMutate).not.toHaveBeenCalled();
    expect(result.current.rulesError).toBe(NO_RULE_MESSAGE);
  });

  it("reports no rule switched on while the rule fields are mounted, as in the panel", async () => {
    const { result } = renderForm("create");

    act(() => {
      result.current.form.register(`rules.${GuardrailTypes.PII}.enabled`);
      result.current.form.setValue("name", "support-bot");
    });
    await submit(result);

    expect(createMutate).not.toHaveBeenCalled();
    expect(result.current.rulesError).toBe(NO_RULE_MESSAGE);

    await act(async () => {
      result.current.form.setValue(
        `rules.${GuardrailTypes.PROMPT_INJECTION}.enabled`,
        true,
      );
    });

    expect(result.current.rulesError).toBeUndefined();
  });

  it("opens a duplicate under a name that is not taken", () => {
    const { result } = renderForm("duplicate", createPolicy(), [
      "support-bot",
      "Support-bot (copy)",
    ]);

    expect(result.current.form.getValues("name")).toBe("support-bot (copy 2)");
  });

  it("re-seeds from the policy when the panel opens again", () => {
    const { result, rerender } = renderForm("edit", createPolicy());

    act(() => {
      result.current.form.setValue("name", "typed but not saved");
    });
    rerender({ open: false });
    rerender({ open: true });

    expect(result.current.form.getValues("name")).toBe("support-bot");
  });
});
