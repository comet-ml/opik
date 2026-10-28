import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { HttpStatusCode } from "axios";
import get from "lodash/get";
import isEqual from "lodash/isEqual";

import { useToast } from "@/ui/use-toast";
import { getApiErrorMessage } from "@/lib/api-error";
import useGuardrailPolicyCreateMutation, {
  GuardrailPolicyPayload,
} from "@/api/guardrail-policies/useGuardrailPolicyCreateMutation";
import useGuardrailPolicyUpdateMutation from "@/api/guardrail-policies/useGuardrailPolicyUpdateMutation";
import {
  GUARDRAIL_EXECUTION_SCOPE,
  GuardrailPolicy,
  GuardrailPolicyRuleType,
} from "@/types/guardrails";
import { GUARDRAIL_RULE_TYPES_ORDER } from "@/constants/guardrails";
import {
  DEFAULT_RULES_STATE,
  RulesState,
  toRulesPayload,
  toRulesState,
  validateRules,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/rulesState";

export type GuardrailPolicyPanelMode = "create" | "edit" | "duplicate";

/** The backend caps the name at 255 characters, so the field does too. */
export const POLICY_NAME_MAX_LENGTH = 255;

/**
 * "X (copy)", then "X (copy 2)" and so on past the names already taken, so duplicating twice does
 * not walk the user into a 409. Names are compared case-insensitively, as the backend matches them.
 * Only the names the caller knows about are considered: a collision with a policy on another page
 * still comes back as an inline error on the name field.
 */
const buildCopyName = (name: string, takenNames: string[]): string => {
  const taken = new Set(takenNames.map((value) => value.toLowerCase()));

  // The base is trimmed to leave room for the suffix, rather than the whole name being cut after
  // the fact, which would make every attempt collapse to the same 255-character string.
  const withSuffix = (attempt: number) => {
    const suffix = attempt === 1 ? " (copy)" : ` (copy ${attempt})`;
    return `${name.slice(0, POLICY_NAME_MAX_LENGTH - suffix.length)}${suffix}`;
  };

  let attempt = 1;
  let candidate = withSuffix(attempt);

  while (taken.has(candidate.toLowerCase())) {
    attempt += 1;
    candidate = withSuffix(attempt);
  }

  return candidate;
};

type UseGuardrailPolicyFormParams = {
  mode: GuardrailPolicyPanelMode;
  policy?: GuardrailPolicy;
  /** Names already taken, so a duplicate opens under one that does not collide. */
  takenNames?: string[];
  open: boolean;
  setOpen: (open: boolean) => void;
};

const useGuardrailPolicyForm = ({
  mode,
  policy,
  takenNames,
  open,
  setOpen,
}: UseGuardrailPolicyFormParams) => {
  const { toast } = useToast();
  const isEdit = mode === "edit";

  const initialName = useMemo(() => {
    if (!policy) return "";
    if (mode !== "duplicate") return policy.name;

    return buildCopyName(policy.name, takenNames ?? []);
  }, [policy, mode, takenNames]);

  const [name, setName] = useState(initialName);
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [description, setDescription] = useState(policy?.description ?? "");
  const [executionScope, setExecutionScope] =
    useState<GUARDRAIL_EXECUTION_SCOPE>(
      policy?.execution_scope ?? GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
    );
  const [rules, setRules] = useState<RulesState>(() =>
    policy ? toRulesState(policy.rules) : DEFAULT_RULES_STATE,
  );
  const [showValidation, setShowValidation] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { mutate: createMutate } = useGuardrailPolicyCreateMutation();
  const { mutate: updateMutate } = useGuardrailPolicyUpdateMutation();

  // Keyed on the policy's id rather than the policy object: a list refetch hands back an equal but
  // freshly allocated row, and re-seeding on that would wipe whatever the user is part-way through
  // typing. Read through a ref so the effect does not depend on the object's identity.
  const latestPolicyRef = useRef(policy);
  latestPolicyRef.current = policy;

  const initialNameRef = useRef(initialName);
  initialNameRef.current = initialName;

  // Re-seed from the policy every time the panel opens: the same panel instance is reused for
  // whichever row the user opens next.
  useEffect(() => {
    if (!open) return;

    const current = latestPolicyRef.current;

    setName(initialNameRef.current);
    setNameError(undefined);
    setDescription(current?.description ?? "");
    setExecutionScope(
      current?.execution_scope ?? GUARDRAIL_EXECUTION_SCOPE.ON_REQUEST,
    );
    setRules(current ? toRulesState(current.rules) : DEFAULT_RULES_STATE);
    setShowValidation(false);
    setIsSubmitting(false);
  }, [open, policy?.id]);

  const updateRule = useCallback(
    <T extends GuardrailPolicyRuleType>(
      type: T,
      patch: Partial<RulesState[T]>,
    ) => {
      setRules((previous) => ({
        ...previous,
        [type]: { ...previous[type], ...patch },
      }));
    },
    [],
  );

  const toggleRule = useCallback((type: GuardrailPolicyRuleType) => {
    setRules((previous) => ({
      ...previous,
      [type]: { ...previous[type], enabled: !previous[type].enabled },
    }));
  }, []);

  const ruleErrors = useMemo(() => validateRules(rules), [rules]);
  const hasEnabledRule = GUARDRAIL_RULE_TYPES_ORDER.some(
    (type) => rules[type].enabled,
  );

  // The only message with no field of its own to sit on: nothing is switched on, so there is no
  // control to point at. Everything else shows on its own field through `ruleErrors`.
  const rulesError = hasEnabledRule
    ? undefined
    : "A policy needs at least one rule. Switch on the checks it should run.";

  const hasFieldErrors = Object.keys(ruleErrors).length > 0;

  const handleError = useCallback(
    (error: Error) => {
      setIsSubmitting(false);

      if (get(error, ["response", "status"]) === HttpStatusCode.Conflict) {
        setNameError("A policy with this name already exists");
        return;
      }

      toast({
        title: "Error",
        description: getApiErrorMessage(error, "Failed to save the policy"),
        variant: "destructive",
      });
    },
    [toast],
  );

  const payload = useMemo<GuardrailPolicyPayload>(
    () => ({
      name: name.trim(),
      ...(description.trim() && { description: description.trim() }),
      execution_scope: executionScope,
      rules: toRulesPayload(rules),
    }),
    [name, description, executionScope, rules],
  );

  // Compared against the stored policy put through the same form round-trip, so a difference only
  // in rule order does not read as an edit.
  const isDirty = useMemo(() => {
    if (!isEdit || !policy) return true;

    const stored: GuardrailPolicyPayload = {
      name: policy.name,
      ...(policy.description && { description: policy.description }),
      execution_scope: policy.execution_scope,
      rules: toRulesPayload(toRulesState(policy.rules)),
    };

    return !isEqual(payload, stored);
  }, [isEdit, policy, payload]);

  const submitHandler = useCallback(() => {
    if (isSubmitting) return;

    setShowValidation(true);

    if (!name.trim()) {
      setNameError("Name is required");
      return;
    }
    if (rulesError || hasFieldErrors) return;

    setIsSubmitting(true);
    setNameError(undefined);

    const onSuccess = () => {
      setIsSubmitting(false);
      setOpen(false);
      toast({
        title: isEdit ? "Policy updated" : "Policy created",
        description: `"${payload.name}" is ready to use.`,
      });
    };

    if (isEdit && policy) {
      updateMutate(
        { policyId: policy.id, policy: payload },
        { onSuccess, onError: handleError },
      );
    } else {
      createMutate({ policy: payload }, { onSuccess, onError: handleError });
    }
  }, [
    isSubmitting,
    rulesError,
    hasFieldErrors,
    name,
    payload,
    isEdit,
    policy,
    updateMutate,
    createMutate,
    handleError,
    setOpen,
    toast,
  ]);

  const isRenamed = isEdit && !!policy && name.trim() !== policy.name;

  return {
    name,
    setName,
    nameError,
    setNameError,
    description,
    setDescription,
    executionScope,
    setExecutionScope,
    rules,
    updateRule,
    toggleRule,
    ruleErrors: showValidation ? ruleErrors : {},
    rulesError: showValidation ? rulesError : undefined,
    isRenamed,
    isEdit,
    isDirty,
    isSubmitting,
    submitHandler,
  };
};

export default useGuardrailPolicyForm;
