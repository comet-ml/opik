import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { GuardrailPolicy } from "@/types/guardrails";
import useGuardrailPolicyBatchDeleteMutation from "@/api/guardrail-policies/useGuardrailPolicyBatchDeleteMutation";

type GuardrailPoliciesActionsPanelProps = {
  policies: GuardrailPolicy[];
};

const GuardrailPoliciesActionsPanel: React.FunctionComponent<
  GuardrailPoliciesActionsPanelProps
> = ({ policies }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState(false);
  const { toast } = useToast();
  const disabled = !policies.length;

  const { mutate } = useGuardrailPolicyBatchDeleteMutation();

  const handleDeleteConfirm = useCallback(() => {
    const count = policies.length;

    mutate(
      { ids: policies.map((policy) => policy.id) },
      {
        onSuccess: () =>
          toast({
            title: count === 1 ? "Policy deleted" : "Policies deleted",
            description: `${count} ${
              count === 1 ? "policy" : "policies"
            } no longer protect this workspace.`,
          }),
      },
    );
  }, [policies, mutate, toast]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={handleDeleteConfirm}
        title={policies.length === 1 ? "Delete policy" : "Delete policies"}
        description="Deleting a policy removes the protection it provides, and any application that still names it will fail. This action can't be undone. Are you sure you want to continue?"
        confirmText={
          policies.length === 1 ? "Delete policy" : "Delete policies"
        }
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-2xs"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default GuardrailPoliciesActionsPanel;
