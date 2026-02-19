import React, { useCallback, useMemo, useState } from "react";
import { MessageCircleWarning } from "lucide-react";
import uniqid from "uniqid";

import useFeedbackDefinitionCreateMutation from "@/api/feedback-definitions/useFeedbackDefinitionCreateMutation";
import useFeedbackDefinitionUpdateMutation from "@/api/feedback-definitions/useFeedbackDefinitionUpdateMutation";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import ColorPicker from "@/components/shared/ColorPicker/ColorPicker";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Textarea } from "@/components/ui/textarea";
import { DEFAULT_HEX_COLOR } from "@/constants/colorVariants";
import { resolveHexColor } from "@/lib/colorVariants";
import useUpdateColorMapping from "@/hooks/useUpdateColorMapping";
import useWorkspaceColorMap from "@/hooks/useWorkspaceColorMap";
import useAppStore from "@/store/AppStore";
import {
  CreateFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import FeedbackDefinitionDetails from "./FeedbackDefinitionDetails";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const TYPE_OPTIONS = [
  {
    value: FEEDBACK_DEFINITION_TYPE.boolean,
    label: "Boolean",
    description: 'Use binary labels (e.g. "Pass", "Fail") to evaluate outputs.',
  },
  {
    value: FEEDBACK_DEFINITION_TYPE.categorical,
    label: "Categorical",
    description:
      'Use labels (e.g. "Helpful", "Neutral", "Unhelpful") to classify outputs.',
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

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
    return (
      feedbackDefinition.details.true_label.trim().length > 0 &&
      feedbackDefinition.details.false_label.trim().length > 0
    );
  }

  return false;
}

type FeedbackDefinitionDialogMode = "create" | "edit" | "clone";

type AddEditFeedbackDefinitionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  feedbackDefinition?: FeedbackDefinition;
  mode?: FeedbackDefinitionDialogMode;
  onCreated?: (feedbackDefinition: CreateFeedbackDefinition) => void;
};

const AddEditFeedbackDefinitionDialog: React.FunctionComponent<
  AddEditFeedbackDefinitionDialogProps
> = ({ open, setOpen, feedbackDefinition, mode = "create", onCreated }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const feedbackDefinitionCreateMutation =
    useFeedbackDefinitionCreateMutation();
  const feedbackDefinitionUpdateMutation =
    useFeedbackDefinitionUpdateMutation();

  const { getColor } = useWorkspaceColorMap();
  const { updateColor } = useUpdateColorMapping();

  const [name, setName] = useState<CreateFeedbackDefinition["name"]>(
    mode === "clone" && feedbackDefinition
      ? `${feedbackDefinition.name} (Copy)`
      : feedbackDefinition?.name ?? "",
  );
  const [description, setDescription] = useState<
    CreateFeedbackDefinition["description"]
  >(feedbackDefinition?.description ?? "");
  const [type, setType] = useState<CreateFeedbackDefinition["type"]>(
    feedbackDefinition?.type ?? FEEDBACK_DEFINITION_TYPE.boolean,
  );
  const [details, setDetails] = useState<
    CreateFeedbackDefinition["details"] | undefined
  >(feedbackDefinition?.details ?? undefined);

  const resolvedColor = resolveHexColor(getColor(name || uniqid()));
  const [localColor, setLocalColor] = useState<string>(
    resolvedColor ?? DEFAULT_HEX_COLOR,
  );

  const isEdit = mode === "edit";
  const title =
    mode === "clone"
      ? "Clone feedback definition"
      : mode === "edit"
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
      description,
    } as CreateFeedbackDefinition;
  }, [details, name, type, description]);

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
      onCreated?.(composedFeedbackDefinition);
    }

    const defaultColor = resolveHexColor(
      getColor(composedFeedbackDefinition.name),
    );
    if (localColor !== defaultColor) {
      updateColor(composedFeedbackDefinition.name, localColor);
    }

    setOpen(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    composedFeedbackDefinition,
    workspaceName,
    feedbackDefinition,
    isEdit,
    localColor,
    getColor,
    updateColor,
  ]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {isEdit && (
            <ExplainerCallout
              Icon={MessageCircleWarning}
              className="mb-2"
              isDismissable={false}
              {...EXPLAINERS_MAP[
                EXPLAINER_ID.what_happens_if_i_edit_a_feedback_definition
              ]}
            />
          )}
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="feedbackDefinitionName">Name</Label>
            <div className="flex items-center">
              <Popover>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    className="size-10 shrink-0 rounded-l-md focus-visible:outline-none"
                    style={{ backgroundColor: localColor }}
                  />
                </PopoverTrigger>
                <PopoverContent className="w-auto" align="start">
                  <ColorPicker value={localColor} onChange={setLocalColor} />
                </PopoverContent>
              </Popover>
              <Input
                id="feedbackDefinitionName"
                className="flex-1 rounded-l-none"
                placeholder="Feedback definition name"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </div>
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="feedbackDefinitionDescription">Description</Label>
            <Textarea
              id="feedbackDefinitionDescription"
              placeholder="Feedback definition description"
              className="min-h-20"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={255}
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
          <div className="flex flex-col gap-4">
            <FeedbackDefinitionDetails
              onChange={setDetails}
              type={type}
              details={details}
            />
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValidFeedbackDefinition(composedFeedbackDefinition)}
            onClick={submitHandler}
          >
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditFeedbackDefinitionDialog;
