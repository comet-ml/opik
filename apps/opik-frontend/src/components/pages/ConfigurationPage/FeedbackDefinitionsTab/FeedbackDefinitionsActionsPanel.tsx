import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import useFeedbackDefinitionBatchDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type FeedbackDefinitionsActionsPanelsProps = {
  feedbackDefinitions: FeedbackDefinition[];
};

const FeedbackDefinitionsActionsPanel: React.FunctionComponent<
  FeedbackDefinitionsActionsPanelsProps
> = ({ feedbackDefinitions }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !feedbackDefinitions?.length;

  const { mutate } = useFeedbackDefinitionBatchDeleteMutation();

  const deleteFeedbackDefinitionsHandler = useCallback(() => {
    mutate({
      ids: feedbackDefinitions.map((f) => f.id),
    });
  }, [feedbackDefinitions, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteFeedbackDefinitionsHandler}
        title="Delete feedback definitions"
        description="Are you sure you want to delete all selected feedback definitions?"
        confirmText="Delete feedback definitions"
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
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default FeedbackDefinitionsActionsPanel;
