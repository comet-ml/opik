import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { EvaluatorsRule } from "@/types/automations";

type RulesActionsPanelsProps = {
  rules: EvaluatorsRule[];
};

const RulesActionsPanel: React.FunctionComponent<RulesActionsPanelsProps> = ({
  rules,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !rules?.length;

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRulesHandler = useCallback(() => {
    mutate({
      ids: rules.map((p) => p.id),
    });
  }, [rules, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteRulesHandler}
        title="Delete evaluation rules"
        description={`Deleting online evaluation rules will stop scoring for all new traces. Existing traces that have already been scores won’t be affected. This action can’t be undone. Are you sure you want to continue?

Tip: To pause scoring without deleting, disable the rules.`}
        confirmText="Delete evaluation rules"
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-sm"
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

export default RulesActionsPanel;
