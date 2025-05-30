import React, { useCallback, useState } from "react";
import { EditorView } from "@codemirror/view";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Description } from "@/components/ui/description";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { DATASET_ITEM_SOURCE, DatasetItem } from "@/types/datasets";
import useAppStore from "@/store/AppStore";
import useDatasetItemBatchMutation from "@/api/datasets/useDatasetItemBatchMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const DATA_PREFILLED_CONTENT = `{
  "input": "<user question>",
  "expected_output": "<expected response>",
  "<any additional fields>": "<any value>"
}`;

type AddDatasetItemDialogProps = {
  datasetItem?: DatasetItem;
  datasetId: string;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditDatasetItemDialog: React.FunctionComponent<
  AddDatasetItemDialogProps
> = ({ datasetItem, datasetId, open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const theme = useCodemirrorTheme({
    editable: true,
  });
  const datasetItemBatchMutation = useDatasetItemBatchMutation();
  const [data, setData] = useState<string>(
    datasetItem?.data
      ? JSON.stringify(datasetItem.data, null, 2)
      : DATA_PREFILLED_CONTENT,
  );
  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});

  const isValid = Boolean(data.length);
  const isEdit = Boolean(datasetItem);
  const title = isEdit ? "Edit dataset item" : "Create a new dataset item";
  const submitText = isEdit ? "Update dataset item" : "Create dataset item";

  const submitHandler = useCallback(() => {
    const valid = isValidJsonObject(data);

    if (valid) {
      datasetItemBatchMutation.mutate({
        datasetId,
        datasetItems: [
          {
            ...datasetItem,
            data: safelyParseJSON(data),
            source: datasetItem?.source ?? DATASET_ITEM_SOURCE.manual,
          },
        ],
        workspaceName,
      });
      setOpen(false);
    } else {
      setShowInvalidJSON(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, datasetId, datasetItem, workspaceName, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="max-h-[70vh] overflow-y-auto">
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="input">Data</Label>
            <div className="max-h-52 overflow-y-auto rounded-md">
              <CodeMirror
                theme={theme}
                value={data}
                onChange={setData}
                extensions={[jsonLanguage, EditorView.lineWrapping]}
              />
            </div>
            <Description>
              {
                EXPLAINERS_MAP[
                  EXPLAINER_ID.what_format_is_this_to_add_my_dataset_item
                ].description
              }
            </Description>
          </div>
          {showInvalidJSON && (
            <Alert variant="destructive">
              <AlertTitle>Invalid JSON</AlertTitle>
            </Alert>
          )}
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" disabled={!isValid} onClick={submitHandler}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditDatasetItemDialog;
