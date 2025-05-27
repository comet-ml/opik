import React, { useCallback, useMemo, useState } from "react";

import useFeedbackDefinitionCreateMutation from "@/api/feedback-definitions/useFeedbackDefinitionCreateMutation";
import useFeedbackDefinitionUpdateMutation from "@/api/feedback-definitions/useFeedbackDefinitionUpdateMutation";
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
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import FeedbackDefinitionDetails from "./FeedbackDefinitionDetails";

const TYPE_OPTIONS = [
  {
    value: FEEDBACK_DEFINITION_TYPE.categorical,
    label: "Categorical",
    description:
      'Use labels (e.g. "Good", "Bad") to classify outputs qualitatively.',
  },
  {
    value: FEEDBACK_DEFINITION_TYPE.numerical,
    label: "Numerical",
    description:
      "Use a numerical range (e.g. 1–5) to rate outputs quantitatively.",
  },
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

type AddEditFeedbackDefinitionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  feedbackDefinition?: FeedbackDefinition;
};

const AddEditFeedbackDefinitionDialog: React.FunctionComponent<
  AddEditFeedbackDefinitionDialogProps
> = ({ open, setOpen, feedbackDefinition }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const feedbackDefinitionCreateMutation =
    useFeedbackDefinitionCreateMutation();
  const feedbackDefinitionUpdateMutation =
    useFeedbackDefinitionUpdateMutation();
  const [name, setName] = useState<CreateFeedbackDefinition["name"]>(
    feedbackDefinition?.name ?? "",
  );
  const [type, setType] = useState<CreateFeedbackDefinition["type"]>(
    feedbackDefinition?.type ?? FEEDBACK_DEFINITION_TYPE.categorical,
  );
  const [details, setDetails] = useState<
    CreateFeedbackDefinition["details"] | undefined
  >(feedbackDefinition?.details ?? undefined);

  const isEdit = Boolean(feedbackDefinition);
  const title = isEdit
    ? "Edit feedback definition"
    : "Create a new feedback definition";
  const submitText = isEdit
    ? "Update feedback definition"
    : "Create feedback definition";

  const composedFeedbackDefinition = useMemo(() => {
    if (!details) return null;

    return {
      details,
      name,
      type,
    } as CreateFeedbackDefinition;
  }, [details, name, type]);

  const submitHandler = useCallback(() => {
    if (!composedFeedbackDefinition) return;

    if (isEdit) {
      feedbackDefinitionUpdateMutation.mutate({
        feedbackDefinition: {
          ...feedbackDefinition,
          ...composedFeedbackDefinition,
        },
      });
    } else {
      feedbackDefinitionCreateMutation.mutate({
        feedbackDefinition: composedFeedbackDefinition,
        workspaceName,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [composedFeedbackDefinition, workspaceName, feedbackDefinition, isEdit]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
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
          <Label htmlFor="feedbackDefinitionType">Type</Label>
          <SelectBox
            id="feedbackDefinitionType"
            value={type}
            onChange={(type) => {
              setDetails(undefined);
              setType(type as CreateFeedbackDefinition["type"]);
            }}
            options={TYPE_OPTIONS}
          />
        </div>
        <div className="flex max-h-[400px] flex-col gap-4 overflow-y-auto">
          <FeedbackDefinitionDetails
            onChange={setDetails}
            type={type}
            details={details}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValidFeedbackDefinition(composedFeedbackDefinition)}
              onClick={submitHandler}
            >
              {submitText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditFeedbackDefinitionDialog;
