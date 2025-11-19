import React, { useState } from "react";
import { 
  Dialog, 
  DialogContent, 
  DialogHeader, 
  DialogTitle, 
  DialogAutoScrollBody, 
  DialogFooter 
} from "@/components/ui/dialog";
import Editor from "@monaco-editor/react";
import { Button } from "@/components/ui/button";
import { DialogClose } from "@radix-ui/react-dialog";
import { useToast } from "@/components/ui/use-toast";

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
  latestMetadata
}: UpdateExperimentDialogProps) {
  const { toast } = useToast();
  const [name, setName] = useState("");
  const [metadata, setMetadata] = useState(
    JSON.stringify(latestMetadata || {}, null, 2)
  );

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
            <label className="block text-sm font-medium mb-1">Name</label>
            <input
              type="text"
              className="w-full border rounded-md px-3 py-1 text-sm focus:outline-none focus:ring focus:ring-ring"
              placeholder={latestName}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2 pb-4">
            <label className="block text-sm font-medium mb-1">Metadata</label>
            <Editor
              height="160px"
              defaultLanguage="json"
              value={metadata}
              onChange={(val) => setMetadata(val || "")}
              options={{
                minimap: { enabled: false },
                lineNumbers: "off",
                glyphMargin: false,
                folding: false,
                lineDecorationsWidth: 0,
                lineNumbersMinChars: 0,
                renderLineHighlight: "none",
                overviewRulerLanes: 0,
                scrollbar: {
                  verticalScrollbarSize: 6,
                  horizontalScrollbarSize: 6,
                },
                fontSize: 13,
                wordWrap: "on",
                scrollBeyondLastLine: false,
                guides: {
                  indentation: false
                } 
              }}
              className="rounded-md border border-gray-300 text-sm"
            />
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">
              Cancel
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" onClick={handleUpdate}>
              Update Experiment
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
