import React, { useCallback, useMemo, useState } from "react";

import useFeedbackDefinitionCreateMutation from "@/api/feedback-definitions/useFeedbackDefinitionCreateMutation";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import useAppStore from "@/store/AppStore";
import {
  CreateFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import FeedbackDefinitionDetails from "./FeedbackDefinitionDetails";

type AddFeedbackDefinitionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const TYPE_OPTIONS = [
  { value: FEEDBACK_DEFINITION_TYPE.categorical, label: "Categorical" },
  { value: FEEDBACK_DEFINITION_TYPE.numerical, label: "Numerical" },
];

function isValidFeedbackDefinition(
  feedbackDefinition: CreateFeedbackDefinition | null,
) {
  if (!feedbackDefinition || !feedbackDefinition.name.length) return false;

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
    return feedbackDefinition.details.min < feedbackDefinition.details.max;
  }

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    return Object.keys(feedbackDefinition.details.categories).length >= 2;
  }

  return false;
}

const AddFeedbackDefinitionDialog: React.FunctionComponent<
  AddFeedbackDefinitionDialogProps
> = ({ open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const feedbackDefinitionCreateMutation =
    useFeedbackDefinitionCreateMutation();
  const [name, setName] = useState<CreateFeedbackDefinition["name"]>("");
  const [type, setType] = useState<CreateFeedbackDefinition["type"]>(
    FEEDBACK_DEFINITION_TYPE.categorical,
  );
  const [details, setDetails] = useState<
    CreateFeedbackDefinition["details"] | null
  >(null);

  const feedbackDefinition = useMemo(() => {
    if (!details) return null;

    return {
      details,
      name,
      type,
    } as CreateFeedbackDefinition;
  }, [details, name, type]);

  const createFeedbackDefinition = useCallback(() => {
    if (!feedbackDefinition) return;

    feedbackDefinitionCreateMutation.mutate({
      feedbackDefinition,
      workspaceName,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedbackDefinition, workspaceName]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Create a new feedback definition</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="feedbackDefinitionName">Name</Label>
          <Input
            id="feedbackDefinitionName"
            placeholder="Feedback definition name"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetDescription">Type</Label>
          <SelectBox
            value={type}
            onChange={(type) => {
              setDetails(null);
              setType(type as CreateFeedbackDefinition["type"]);
            }}
            options={TYPE_OPTIONS}
            width="100px"
          />
        </div>
        <div className="flex max-h-[400px] flex-col gap-4 overflow-y-auto">
          <FeedbackDefinitionDetails onChange={setDetails} type={type} />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValidFeedbackDefinition(feedbackDefinition)}
              onClick={createFeedbackDefinition}
            >
              Create feedback definition
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddFeedbackDefinitionDialog;
