import { useCallback, useEffect, useMemo, useRef } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { HttpStatusCode } from "axios";
import get from "lodash/get";

import { useToast } from "@/ui/use-toast";
import { getApiErrorMessage } from "@/lib/api-error";
import useGuardrailPolicyCreateMutation from "@/api/guardrail-policies/useGuardrailPolicyCreateMutation";
import useGuardrailPolicyUpdateMutation from "@/api/guardrail-policies/useGuardrailPolicyUpdateMutation";
import { GuardrailPolicy } from "@/types/guardrails";
import {
  GuardrailPolicyFormSchema,
  GuardrailPolicyFormValues,
  POLICY_NAME_MAX_LENGTH,
  toFormValues,
  toPolicyPayload,
} from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/schema";

export type GuardrailPolicyPanelMode = "create" | "edit" | "duplicate";

export const NAME_TAKEN_MESSAGE = "A policy with this name already exists";

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

  const form = useForm<GuardrailPolicyFormValues>({
    resolver: zodResolver(GuardrailPolicyFormSchema),
    defaultValues: toFormValues(policy, initialName),
  });

  const { mutateAsync: createPolicy } = useGuardrailPolicyCreateMutation();
  const { mutateAsync: updatePolicy } = useGuardrailPolicyUpdateMutation();

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

    form.reset(toFormValues(latestPolicyRef.current, initialNameRef.current));
  }, [open, policy?.id, form]);

  // After a submit attempt, a rule change re-validates all the rules: the form re-validates only the
  // changed field, which would leave a message about the rules as a whole (none switched on, topics
  // in both lists) standing after it is fixed.
  useEffect(() => {
    const subscription = form.watch((_, { name }) => {
      if (name?.startsWith("rules.") && form.formState.isSubmitted) {
        form.trigger("rules");
      }
    });
    return () => subscription.unsubscribe();
  }, [form]);

  const handleError = useCallback(
    (error: Error) => {
      if (get(error, ["response", "status"]) === HttpStatusCode.Conflict) {
        form.setError("name", { message: NAME_TAKEN_MESSAGE });
        return;
      }

      toast({
        title: "Error",
        description: getApiErrorMessage(error, "Failed to save the policy"),
        variant: "destructive",
      });
    },
    [form, toast],
  );

  const submitHandler = useMemo(
    () =>
      // Awaiting the request keeps formState.isSubmitting true until it settles.
      form.handleSubmit(async (values) => {
        const payload = toPolicyPayload(values);

        try {
          if (isEdit && policy) {
            await updatePolicy({ policyId: policy.id, policy: payload });
          } else {
            await createPolicy({ policy: payload });
          }
        } catch (error) {
          handleError(error as Error);
          return;
        }

        setOpen(false);
        toast({
          title: isEdit ? "Policy updated" : "Policy created",
          description: `"${payload.name}" is ready to use.`,
        });
      }),
    [
      form,
      isEdit,
      policy,
      updatePolicy,
      createPolicy,
      handleError,
      setOpen,
      toast,
    ],
  );

  const name = form.watch("name");
  const isRenamed = isEdit && !!policy && name.trim() !== policy.name;

  // Creating or duplicating always has something to save; an edit only once a value changed.
  const isDirty = !isEdit || form.formState.isDirty;
  const isSubmitting = form.formState.isSubmitting;

  // The resolver puts a message about "rules" on rules.root once its fields are mounted, as it does
  // for a field array, and on "rules" itself before that.
  const rulesErrors = form.formState.errors.rules;
  const rulesError: string | undefined =
    get(rulesErrors, ["root", "message"]) ?? rulesErrors?.message;

  return {
    form,
    rulesError,
    isRenamed,
    isEdit,
    isDirty,
    isSubmitting,
    submitHandler,
  };
};

export default useGuardrailPolicyForm;
