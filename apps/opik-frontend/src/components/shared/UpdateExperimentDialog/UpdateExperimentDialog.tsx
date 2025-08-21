import { 
  Dialog, 
  DialogTrigger, 
  DialogContent, 
  DialogHeader, 
  DialogTitle, 
  DialogAutoScrollBody, 
  DialogFooter 
} from "@/components/ui/dialog" 

import { Button } from "@/components/ui/button";
import { useState } from "react";
import { DialogClose } from "@radix-ui/react-dialog";

export function UpdateExperimentDialog({
  open,
  setOpen,
  onConfirm
}) {

  const [name, setName] = useState("")
  const [metadata, setMetadata] = useState("")

  return (
    <Dialog
    open={open}
    onOpenChange={setOpen}
    >
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Enter Details</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody className="space-y-4">
          {/* Name Input */}
          <div>
            <label className="block text-sm font-medium mb-1">Name</label>
            <input
              type="text"
              className="w-full border rounded-md px-3 py-1 text-sm focus:outline-none focus:ring focus:ring-ring"
              placeholder="Enter name"
              onChange={(val) => {
                setName(val.target.value)
              }}
            />
          </div>

          {/* Metadata Input */}
          <div>
            <label className="block text-sm font-medium mb-1">Metadata</label>
            <textarea
              className="w-full border rounded-md px-3 py-2 text-sm h-32 resize-y focus:outline-none focus:ring focus:ring-ring"
              placeholder="Enter metadata"
              onChange={(val) => {
                setMetadata(val.target.value)
              }}
            />
          </div>
        </DialogAutoScrollBody>
        <DialogClose>
          <Button type="submit"
            onClick={() => {
              onConfirm(name, metadata)
            }}
          >Save</Button>
        </DialogClose>
      </DialogContent>
    </Dialog>
  );
}
