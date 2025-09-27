import { 
  Dialog, 
  DialogTrigger, 
  DialogContent, 
  DialogHeader, 
  DialogTitle, 
  DialogAutoScrollBody, 
  DialogFooter 
} from "@/components/ui/dialog" 
import Editor from "@monaco-editor/react";
import { Button } from "@/components/ui/button";
import { useState } from "react";
import { DialogClose } from "@radix-ui/react-dialog";

export function UpdateExperimentDialog({
  open,
  setOpen,
  onConfirm,
  latestName,
  latestMetadata
}) {

  const [name, setName] = useState("")
  const [metadata, setMetadata] = useState(
    JSON.stringify(latestMetadata, null, 2)
  );

  return (
    <Dialog
    open={open}
    onOpenChange={setOpen}
    >
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Edit experiment</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="space-y-4">
          {/* Name Input */}
          <div className="flex flex-col gap-2 pb-4">
            <label className="block text-sm font-medium mb-1">Name</label>
            <input
              type="text"
              className="w-full border rounded-md px-3 py-1 text-sm focus:outline-none focus:ring focus:ring-ring"
              placeholder={latestName}
              onChange={(val) => {
                setName(val.target.value)
              }}
            />
          </div>

          {/* Metadata Input */}
          <div className="flex flex-col gap-2 pb-4">
            {/* <label className="block text-sm font-medium mb-1">Metadata</label> */}
            {/* <textarea
              className="w-full border rounded-md px-3 py-2 text-sm h-32 resize-y focus:outline"
              placeholder={JSON.stringify(latestMetadata, null, 2)}
              onChange={(val) => {
                setMetadata(val.target.value)
              }}
            /> */}

            <div className="flex flex-col gap-2 pb-4">
              <label className="block text-sm font-medium mb-1">Metadata</label>
              <Editor
                height="160px"
                defaultLanguage="json"
                value={metadata}
                onChange={(val) => setMetadata(val || "")}
                options={{
                  minimap: { enabled: false },
                  lineNumbers: "off",          // hide line numbers
                  glyphMargin: false,          // no gutter icons
                  folding: false,              // no folding arrows
                  lineDecorationsWidth: 0,
                  lineNumbersMinChars: 0,
                  renderLineHighlight: "none", // no line highlight
                  overviewRulerLanes: 0,       // remove right margin bar
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
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose>
            <Button variant="outline">
              Cancel
            </Button>
          </DialogClose>
          <DialogClose>
          <Button type="submit"
            onClick={() => {
              let parsedMetadata: object = {};
              try {
                parsedMetadata = metadata ? JSON.parse(metadata) : {};
              } catch (e) {
                alert("Invalid JSON in metadata");
                return;
              }

              // fallback: use latestName/Metadata if input is empty
              onConfirm(name || latestName, parsedMetadata || latestMetadata);
            }}
          >Update Experiment</Button>
        </DialogClose>
        </DialogFooter>
        
      </DialogContent>
    </Dialog>
  );
}
