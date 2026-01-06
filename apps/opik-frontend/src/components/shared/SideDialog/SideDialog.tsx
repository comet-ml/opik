import React from "react";
import { Sheet, SheetContent } from "@/components/ui/sheet";
import { useIsPhone } from "@/hooks/useIsPhone";
import { cn } from "@/lib/utils";

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
  const { isPhonePortrait } = useIsPhone();

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        className={cn(
          "sm:max-w-full xl:w-[calc(100vw-240px)]",
          isPhonePortrait ? "w-[calc(100vw-32px)]" : "w-[calc(100vw-60px)]",
        )}
      >
        {children}
      </SheetContent>
    </Sheet>
  );
};

export default SideDialog;
