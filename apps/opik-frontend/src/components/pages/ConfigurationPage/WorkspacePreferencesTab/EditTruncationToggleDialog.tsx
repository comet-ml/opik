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
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

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
          <DialogDescription>
            {isDisabling
              ? `Disabling truncation will display full content in tables. This may slow performance and limit pagination to ${TRUNCATION_DISABLED_MAX_PAGE_SIZE} items per page.`
              : "Truncating large data improves performance and enables full pagination options. You can change this anytime."}
          </DialogDescription>
        </DialogHeader>

        {isDisabling && (
          <Alert variant="destructive" size="sm">
            <AlertTriangle />
            <AlertTitle size="sm" className="text-destructive">
              Performance impact
            </AlertTitle>
            <AlertDescription size="sm" className="text-foreground">
              Large amounts of data may cause slower page loading and increased
              memory usage.
            </AlertDescription>
          </Alert>
        )}

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button variant="default" onClick={handleConfirm}>
            {isDisabling ? "Disable truncation" : "Enable truncation"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditTruncationToggleDialog;
