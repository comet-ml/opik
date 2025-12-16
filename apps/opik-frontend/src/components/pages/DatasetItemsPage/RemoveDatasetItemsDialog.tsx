import React from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

type RemoveDatasetItemsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
  title?: string;
  description?: string;
  confirmText?: string;
};

const RemoveDatasetItemsDialog: React.FunctionComponent<
  RemoveDatasetItemsDialogProps
> = ({
  open,
  setOpen,
  onConfirm,
  title = "Remove dataset items",
  description = "The items will be deleted from your current dataset view. The changes won't take effect until you save and create a new version.",
  confirmText = "Remove dataset items",
}) => {
  const [dontAskAgain, setDontAskAgain] = useDatasetItemDeletePreference();

  const handleConfirm = () => {
    onConfirm();
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <div className="comet-body-s text-muted-slate">{description}</div>
          <Label
            key="dont-show-again"
            className="flex cursor-pointer items-center gap-2"
          >
            <Checkbox
              id="dont-show-again"
              checked={dontAskAgain}
              onCheckedChange={(v) => setDontAskAgain(v === true)}
            />

            <div className="comet-body-s text-muted-slate">
              Don&apos;t show this message again
            </div>
          </Label>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" variant="destructive" onClick={handleConfirm}>
              {confirmText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RemoveDatasetItemsDialog;
