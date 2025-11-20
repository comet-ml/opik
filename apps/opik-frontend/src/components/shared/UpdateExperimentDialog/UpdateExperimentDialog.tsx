import React, { useEffect, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { DialogClose } from "@radix-ui/react-dialog";
import { useToast } from "@/components/ui/use-toast";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";

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
  const { toast } = useToast();
  const theme = useCodemirrorTheme({
    editable: true,
  });
  const [name, setName] = useState("");
  const [configuration, setConfiguration] = useState(
    JSON.stringify(latestConfiguration || {}, null, 2),
  );

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setName("");
      setConfiguration(JSON.stringify(latestConfiguration || {}, null, 2));
    }
  }, [open, latestConfiguration]);

  // Check if any changes have been made
  const hasChanges =
    name !== "" ||
    configuration !== JSON.stringify(latestConfiguration || {}, null, 2);

  const handleUpdate = () => {
    let parsedConfiguration: object = {};
    try {
      parsedConfiguration = configuration ? JSON.parse(configuration) : {};
    } catch (e) {
      toast({
        title: "Invalid JSON",
        description: "Please provide valid JSON for configuration",
        variant: "destructive",
      });
      return;
    }

    // Use latestName/Configuration if input is empty
    onConfirm(
      name || latestName,
      parsedConfiguration || latestConfiguration || {},
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Edit experiment</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="space-y-4">
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              type="text"
              placeholder={latestName}
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
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" onClick={handleUpdate} disabled={!hasChanges}>
              Update experiment
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
