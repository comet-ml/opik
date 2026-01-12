import React from "react";
import { Sheet, SheetContent } from "@/components/ui/sheet";

type SideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  children: React.ReactNode;
};

const SideDialog: React.FunctionComponent<SideDialogProps> = ({
  open,
  setOpen,
  children,
}) => {
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent className="w-[calc(100vw-32px)] sm:max-w-full md:w-[calc(100vw-60px)] xl:w-[calc(100vw-240px)]">
        {children}
      </SheetContent>
    </Sheet>
  );
};

export default SideDialog;
