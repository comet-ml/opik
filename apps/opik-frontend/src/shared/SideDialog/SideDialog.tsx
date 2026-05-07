import React from "react";
import { Sheet, SheetContent } from "@/ui/sheet";

type SideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  children: React.ReactNode;
  header?: React.ReactNode;
  blockOverlayClose?: boolean;
};

const SideDialog: React.FunctionComponent<SideDialogProps> = ({
  open,
  setOpen,
  children,
  header,
  blockOverlayClose,
}) => {
  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        className="w-[calc(100vw-32px)] sm:max-w-full md:w-[calc(100vw-60px)] xl:w-[calc(100vw-240px)]"
        header={header}
        blockOverlayClose={blockOverlayClose}
      >
        {children}
      </SheetContent>
    </Sheet>
  );
};

export default SideDialog;
