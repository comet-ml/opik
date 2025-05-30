import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Prompt } from "@/types/prompts";
import usePromptBatchDeleteMutation from "@/api/prompts/usePromptBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type PromptsActionsPanelsProps = {
  prompts: Prompt[];
};

const PromptsActionsPanel: React.FunctionComponent<
  PromptsActionsPanelsProps
> = ({ prompts }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !prompts?.length;

  const { mutate } = usePromptBatchDeleteMutation();

  const deletePromptsHandler = useCallback(() => {
    mutate({
      ids: prompts.map((p) => p.id),
    });
  }, [prompts, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deletePromptsHandler}
        title="Delete prompts"
        description="Deleting these prompts will also remove all associated commits. This action cannot be undone. Are you sure you want to continue?"
        confirmText="Delete prompts"
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

export default PromptsActionsPanel;
