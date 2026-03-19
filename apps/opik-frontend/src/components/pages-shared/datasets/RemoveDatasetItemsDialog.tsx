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

const RemoveDatasetItemsDialog = ({
  open,
  setOpen,
  onConfirm,
  title = "Remove suite items",
  description = "The items will be deleted from your current suite view. The changes won't take effect until you save and create a new version.",
  confirmText = "Remove suite items",
}: RemoveDatasetItemsDialogProps) => {
  const [dontAskAgain, setDontAskAgain] = useDatasetItemDeletePreference();

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <div className="comet-body-s text-muted-slate">{description}</div>
          <Label className="flex cursor-pointer items-center gap-2">
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
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" variant="destructive" onClick={onConfirm}>
              {confirmText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RemoveDatasetItemsDialog;
