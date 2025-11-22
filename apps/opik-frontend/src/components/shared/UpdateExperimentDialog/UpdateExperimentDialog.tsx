import React, { useEffect, useRef, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Description } from "@/components/ui/description";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { DialogClose } from "@radix-ui/react-dialog";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface UpdateExperimentDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: (name: string, configuration: object) => void;
  latestName: string;
  latestConfiguration?: object;
}

export function UpdateExperimentDialog({
  open,
  setOpen,
  onConfirm,
  latestName,
  latestConfiguration,
}: UpdateExperimentDialogProps) {
  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });
  const prevOpenRef = useRef(false);
  const [name, setName] = useState(latestName);
  const [configuration, setConfiguration] = useState(
    JSON.stringify(latestConfiguration || {}, null, 2),
  );

  // Reset state only when dialog opens (false -> true transition)
  useEffect(() => {
    if (open && !prevOpenRef.current) {
      setName(latestName);
      setConfiguration(JSON.stringify(latestConfiguration || {}, null, 2));
      setShowInvalidJSON(false);
    }
    prevOpenRef.current = open;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // Check if any changes have been made
  const hasChanges =
    name !== latestName ||
    configuration !== JSON.stringify(latestConfiguration || {}, null, 2);

  // Validate name is not empty
  const isValid = Boolean(name.trim().length);

  const handleUpdate = () => {
    // Validate JSON configuration
    const isConfigValid =
      configuration.trim() === "" || isValidJsonObject(configuration);

    if (!isConfigValid) {
      setShowInvalidJSON(true);
      return;
    }

    // Use validation result directly to avoid double parsing
    const parsedConfiguration =
      configuration.trim() === "" ? {} : safelyParseJSON(configuration);

    onConfirm(name, parsedConfiguration);
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Edit experiment</DialogTitle>
        </DialogHeader>
        <div className="max-h-[70vh] overflow-y-auto">
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="configuration">Configuration</Label>
            <div className="max-h-52 overflow-y-auto rounded-md">
              <CodeMirror
                theme={theme}
                value={configuration}
                onChange={setConfiguration}
                extensions={[jsonLanguage]}
                basicSetup={{
                  lineNumbers: false,
                  foldGutter: false,
                }}
              />
            </div>
            <Description>
              {
                EXPLAINERS_MAP[EXPLAINER_ID.what_format_should_the_metadata_be]
                  .description
              }
            </Description>
          </div>
          {showInvalidJSON && (
            <Alert variant="destructive">
              <AlertTitle>Configuration field is not valid</AlertTitle>
            </Alert>
          )}
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            onClick={handleUpdate}
            disabled={!isValid || !hasChanges}
          >
            Update experiment
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
