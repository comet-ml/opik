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
  onConfirm: (name: string, metadata: object) => void;
  latestName: string;
  latestMetadata?: object;
}

export function UpdateExperimentDialog({
  open,
  setOpen,
  onConfirm,
  latestName,
  latestMetadata,
}: UpdateExperimentDialogProps) {
  const { toast } = useToast();
  const theme = useCodemirrorTheme({
    editable: true,
  });
  const [name, setName] = useState("");
  const [metadata, setMetadata] = useState(
    JSON.stringify(latestMetadata || {}, null, 2),
  );

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setName("");
      setMetadata(JSON.stringify(latestMetadata || {}, null, 2));
    }
  }, [open, latestMetadata]);

  // Check if any changes have been made
  const hasChanges =
    name !== "" || metadata !== JSON.stringify(latestMetadata || {}, null, 2);

  const handleUpdate = () => {
    let parsedMetadata: object = {};
    try {
      parsedMetadata = metadata ? JSON.parse(metadata) : {};
    } catch (e) {
      toast({
        title: "Invalid JSON",
        description: "Please provide valid JSON for metadata",
        variant: "destructive",
      });
      return;
    }

    // Use latestName/Metadata if input is empty
    onConfirm(name || latestName, parsedMetadata || latestMetadata || {});
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
            <Label htmlFor="metadata">Metadata</Label>
            <div className="max-h-52 overflow-y-auto rounded-md">
              <CodeMirror
                theme={theme}
                value={metadata}
                onChange={setMetadata}
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
