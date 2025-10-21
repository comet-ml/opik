import React from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useToast } from "@/components/ui/use-toast";
import { TRUNCATION_DISABLED_MAX_PAGE_SIZE } from "@/constants/shared";
import { AlertTriangle } from "lucide-react";

type EditTruncationToggleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  currentValue: boolean;
  onConfirm: (enabled: boolean) => void;
};

const EditTruncationToggleDialog: React.FC<EditTruncationToggleDialogProps> = ({
  open,
  setOpen,
  currentValue,
  onConfirm,
}) => {
  const { toast } = useToast();

  const handleConfirm = () => {
    const newValue = !currentValue;
    onConfirm(newValue);
    setOpen(false);

    if (!newValue) {
      toast({
        title: "Data truncation disabled",
        description:
          "Pagination has been limited to prevent performance issues with large data.",
      });
    } else {
      toast({
        title: "Data truncation enabled",
        description: "Full pagination options are now available.",
      });
    }
  };

  const isDisabling = currentValue === true;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            {isDisabling ? "Disable" : "Enable"} data truncation?
          </DialogTitle>
          {isDisabling && (
            <DialogDescription>
              Disabling truncation will have the following effects:
            </DialogDescription>
          )}
        </DialogHeader>

        {isDisabling && (
          <div className="space-y-3 py-4">
            <div className="flex items-start gap-2 rounded-md bg-warning/10 p-3">
              <AlertTriangle className="mt-0.5 size-5 shrink-0 text-warning" />
              <div className="space-y-2 text-sm">
                <p className="font-medium">Warning:</p>
                <ul className="list-inside list-disc space-y-1 text-muted-foreground">
                  <li>
                    Pagination limited to maximum{" "}
                    {TRUNCATION_DISABLED_MAX_PAGE_SIZE} items per page
                  </li>
                  <li>Large data may cause performance issues</li>
                  <li>You can re-enable truncation anytime</li>
                </ul>
              </div>
            </div>
          </div>
        )}

        {!isDisabling && (
          <DialogDescription>
            Enable data truncation to improve performance and allow full
            pagination options.
          </DialogDescription>
        )}

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            variant={isDisabling ? "destructive" : "default"}
            onClick={handleConfirm}
          >
            {isDisabling ? "Disable Truncation" : "Enable Truncation"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditTruncationToggleDialog;
