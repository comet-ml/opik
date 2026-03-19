import React, { useState } from "react";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/ui/button";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import useEvaluationSuiteDraftStore, {
  useAddItem,
} from "@/store/EvaluationSuiteDraftStore";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import EvaluationSuiteItemFormContainer from "./EvaluationSuiteItemFormContainer";
import {
  EvaluationSuiteItemFormValues,
  fromFormValues,
} from "./evaluationSuiteItemFormSchema";

const ADD_SUITE_ITEM_FORM_ID = "add-evaluation-suite-item-form";

const DATA_PREFILLED_CONTENT = `{
  "input": "<user question>",
  "expected_output": "<expected response>",
  "<any additional fields>": "<any value>"
}`;

interface AddEvaluationSuiteItemPanelProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
  onOpenSettings: () => void;
}

const AddEvaluationSuiteItemPanelContent: React.FC<{
  columns: DatasetItemColumn[];
  onClose: () => void;
  onOpenSettings: () => void;
}> = ({ columns, onClose, onOpenSettings }) => {
  const [tags, setTags] = useState<string[]>([]);

  const addItem = useAddItem();
  const setItemAssertionsInStore = useEvaluationSuiteDraftStore(
    (s) => s.setItemAssertions,
  );

  const suiteId = useSuiteIdFromURL();
  const suiteAssertions = useEffectiveSuiteAssertions(suiteId);
  const suitePolicy = useEffectiveExecutionPolicy(suiteId);
  const isEmptyDataset = columns.length === 0;
  const initialData = Object.fromEntries(columns.map((col) => [col.name, ""]));

  const initialValues: EvaluationSuiteItemFormValues = {
    description: "",
    data: isEmptyDataset
      ? DATA_PREFILLED_CONTENT
      : JSON.stringify(initialData, null, 2),
    assertions: [],
    runsPerItem: suitePolicy.runs_per_item,
    passThreshold: suitePolicy.pass_threshold,
  };

  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const isDirty = hasUnsavedChanges || tags.length > 0;

  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const onValidSubmit = (values: EvaluationSuiteItemFormValues) => {
    const { description, data, assertions, policy } = fromFormValues(values);

    const now = new Date().toISOString();
    const saveData = data ?? initialData;

    const policyChanged =
      policy.runs_per_item !== suitePolicy.runs_per_item ||
      policy.pass_threshold !== suitePolicy.pass_threshold;

    const tempId = addItem({
      data: saveData,
      description: description || undefined,
      source: DATASET_ITEM_SOURCE.manual,
      tags,
      created_at: now,
      last_updated_at: now,
      ...(policyChanged ? { execution_policy: policy } : {}),
    });
    if (assertions.length > 0) {
      setItemAssertionsInStore(tempId, assertions);
    }
    onClose();
  };

  return (
    <>
      <div className="relative size-full overflow-y-auto">
        <div className="sticky top-0 z-10 border-b bg-background p-6 pb-4">
          <div className="flex items-center justify-between gap-2">
            <div className="comet-body-accented">Add suite item</div>
            <div className="flex items-center gap-2">
              <Button
                variant="default"
                size="sm"
                type="submit"
                form={ADD_SUITE_ITEM_FORM_ID}
              >
                Save changes
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  if (isDirty) {
                    requestConfirm(onClose);
                  } else {
                    onClose();
                  }
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
          <TagListRenderer
            tags={tags}
            onAddTag={(tag) => setTags((prev) => [...prev, tag])}
            onDeleteTag={(tag) =>
              setTags((prev) => prev.filter((t) => t !== tag))
            }
            size="sm"
            align="start"
          />
        </div>

        <EvaluationSuiteItemFormContainer
          formId={ADD_SUITE_ITEM_FORM_ID}
          initialValues={initialValues}
          suiteAssertions={suiteAssertions}
          suitePolicy={suitePolicy}
          onOpenSettings={onOpenSettings}
          onSubmit={onValidSubmit}
          setHasUnsavedChanges={setHasUnsavedChanges}
          showDataDescription={isEmptyDataset}
        />
      </div>

      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={() => cancel()}
        onConfirm={cancel}
        onCancel={confirm}
        title="Discard changes?"
        description="You have unsaved changes. Do you want to discard them and close?"
        confirmText="Keep editing"
        cancelText="Discard changes"
        confirmButtonVariant="default"
      />
    </>
  );
};

const AddEvaluationSuiteItemPanel: React.FC<
  AddEvaluationSuiteItemPanelProps
> = ({ open, onClose, columns, onOpenSettings }) => {
  return (
    <ResizableSidePanel
      panelId="evaluation-suite-item-panel"
      entity="item"
      open={open}
      onClose={onClose}
    >
      {open && (
        <AddEvaluationSuiteItemPanelContent
          columns={columns}
          onClose={onClose}
          onOpenSettings={onOpenSettings}
        />
      )}
    </ResizableSidePanel>
  );
};

export default AddEvaluationSuiteItemPanel;
