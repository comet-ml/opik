import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { EvaluatorsRule } from "@/types/automations";

type RulesActionsPanelsProps = {
  projectId: string;
  rules: EvaluatorsRule[];
};

const RulesActionsPanel: React.FunctionComponent<RulesActionsPanelsProps> = ({
  projectId,
  rules,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !rules?.length;

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRulesHandler = useCallback(() => {
    mutate({
      projectId,
      ids: rules.map((p) => p.id),
    });
  }, [rules, mutate, projectId]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteRulesHandler}
        title="Delete rules"
        description="Are you sure you want to delete all selected rules?"
        confirmText="Delete rules"
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default RulesActionsPanel;
