import React, { useCallback, useEffect, useState } from "react";
import { EditorView } from "@codemirror/view";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
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
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

const validateDatasetItem = (
  input: string,
  output?: string,
  metadata?: string,
) => {
  return (
    isValidJsonObject(input) &&
    (output ? isValidJsonObject(output) : true) &&
    (metadata ? isValidJsonObject(metadata) : true)
  );
};

const ERROR_TIMEOUT = 3000;

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
  const theme = useCodemirrorTheme();
  const datasetItemBatchMutation = useDatasetItemBatchMutation();
  const [input, setInput] = useState<string>(
    datasetItem?.input ? JSON.stringify(datasetItem.input, null, 2) : "",
  );
  const [output, setOutput] = useState<string>(
    datasetItem?.expected_output
      ? JSON.stringify(datasetItem.expected_output, null, 2)
      : "",
  );
  const [metadata, setMetadata] = useState<string>(
    datasetItem?.metadata ? JSON.stringify(datasetItem.metadata, null, 2) : "",
  );
  const [showInvalidJSON, setShowInvalidJSON] = useState<boolean>(false);

  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (showInvalidJSON) {
      timer = setTimeout(() => setShowInvalidJSON(false), ERROR_TIMEOUT);
    }
    return () => {
      clearTimeout(timer);
    };
  }, [showInvalidJSON]);

  const isValid = Boolean(input.length);
  const isEdit = Boolean(datasetItem);
  const title = isEdit ? "Edit dataset item" : "Create a new dataset item";
  const submitText = isEdit ? "Update dataset item" : "Create dataset item";

  const submitHandler = useCallback(() => {
    const valid = validateDatasetItem(input, output, metadata);

    if (valid) {
      datasetItemBatchMutation.mutate({
        datasetId,
        datasetItems: [
          {
            ...datasetItem,
            input: safelyParseJSON(input),
            expected_output: output ? safelyParseJSON(output) : undefined,
            metadata: metadata ? safelyParseJSON(metadata) : undefined,
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
  }, [input, output, metadata, datasetId, datasetItem, workspaceName, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="max-h-[70vh] overflow-y-auto">
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="input">Input</Label>
            <div className="max-h-52 overflow-y-auto">
              <CodeMirror
                theme={theme}
                value={input}
                onChange={setInput}
                extensions={[jsonLanguage, EditorView.lineWrapping]}
              />
            </div>
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="output">Expected output</Label>
            <div className="max-h-52 overflow-y-auto">
              <CodeMirror
                theme={theme}
                value={output}
                onChange={setOutput}
                extensions={[jsonLanguage, EditorView.lineWrapping]}
              />
            </div>
          </div>
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="output">Metadata</Label>
            <div className="max-h-52 overflow-y-auto">
              <CodeMirror
                theme={theme}
                value={metadata}
                onChange={setMetadata}
                extensions={[jsonLanguage, EditorView.lineWrapping]}
              />
            </div>
          </div>
          {showInvalidJSON && (
            <Alert variant="destructive">
              <AlertDescription>Invalid JSON</AlertDescription>
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
