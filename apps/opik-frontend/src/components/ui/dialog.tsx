import * as React from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";

import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";

const Dialog = DialogPrimitive.Root;

const DialogTrigger = DialogPrimitive.Trigger;

const DialogPortal = DialogPrimitive.Portal;

const DialogClose = DialogPrimitive.Close;

const DialogOverlay = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Overlay>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Overlay>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Overlay
    ref={ref}
    className={cn(
      "fixed inset-0 z-50 bg-black/50  data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0",
      className,
    )}
    {...props}
  />
));
DialogOverlay.displayName = DialogPrimitive.Overlay.displayName;

const DialogContent = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content>
>(({ className, children, ...props }, ref) => (
  <DialogPortal>
    <DialogOverlay />
    <DialogPrimitive.Content
      ref={ref}
      className={cn(
        "fixed left-[50%] top-[50%] z-50 grid w-full translate-x-[-50%] translate-y-[-50%] gap-4 border bg-background px-8 py-6 shadow-lg duration-200 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%] sm:rounded-md",
        className,
      )}
      {...props}
    >
      {children}
      <DialogPrimitive.Close className="absolute right-8 top-7 opacity-70 transition-opacity hover:opacity-100 focus:outline-none disabled:pointer-events-none data-[state=open]:bg-accent data-[state=open]:text-muted-foreground">
        <X className="size-4" />
        <span className="sr-only">Close</span>
      </DialogPrimitive.Close>
    </DialogPrimitive.Content>
  </DialogPortal>
));
DialogContent.displayName = DialogPrimitive.Content.displayName;

const DialogHeader = ({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) => (
  <div
    data-dialog-header="true"
    className={cn(
      "flex flex-col space-y-1.5 text-center pb-2 sm:text-left max-w-full min-w-1",
      className,
    )}
    {...props}
  />
);
DialogHeader.displayName = "DialogHeader";

type DialogBodyProps = {
  className?: string;
} & React.HTMLAttributes<HTMLDivElement>;

const DialogAutoScrollBody: React.FC<DialogBodyProps> = ({
  className,
  children,
}) => {
  const [heightObject, setHeightObject] = React.useState<{
    height: number;
    maxHeight: number;
  }>({
    height: 0,
    maxHeight: 0,
  });
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    const vh = Math.max(
      document.documentElement.clientHeight || 0,
      window.innerHeight || 0,
    );
    const contentHeight = node.clientHeight;
    const container = node.parentElement!.parentElement!;
    const headerHeight =
      container.querySelector('[data-dialog-header="true"]')?.clientHeight || 0;
    const footerHeight =
      container.querySelector('[data-dialog-footer="true"]')?.clientHeight || 0;
    const TOP_BOTTOM_PADDING = 80 + 48 + 48; //48 px padding, 48 px spacing
    const MIN_BODY_HEIGHT = 100;
    const maxHeight = Math.max(
      vh - TOP_BOTTOM_PADDING - headerHeight - footerHeight,
      MIN_BODY_HEIGHT,
    );

    setHeightObject((state) => {
      const newState = {
        maxHeight,
        height: contentHeight > maxHeight ? maxHeight : 0,
      };

      if (
        state.height !== newState.height ||
        state.maxHeight != newState.maxHeight
      ) {
        return newState;
      }
      return state;
    });
  }, true);

  const hasScroll = heightObject.height > 0;
  const style = {
    ...(heightObject.height && { height: `${heightObject.height}px` }),
    ...(heightObject.maxHeight && { maxHeight: `${heightObject.maxHeight}px` }),
  };

  return (
    <div
      style={style}
      className={cn(
        "overflow-y-auto",
        hasScroll && "border-b border-t py-4",
        className,
      )}
    >
      <div ref={ref}>{children}</div>
    </div>
  );
};
DialogAutoScrollBody.displayName = "DialogAutoScrollBody";

const DialogFooter = ({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) => (
  <div
    data-dialog-footer="true"
    className={cn(
      "flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2",
      className,
    )}
    {...props}
  />
);
DialogFooter.displayName = "DialogFooter";

const DialogTitle = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Title>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Title>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Title
    ref={ref}
    className={cn("comet-title-s pr-8 break-words", className)}
    {...props}
  />
));
DialogTitle.displayName = DialogPrimitive.Title.displayName;

const DialogDescription = React.forwardRef<
  React.ElementRef<typeof DialogPrimitive.Description>,
  React.ComponentPropsWithoutRef<typeof DialogPrimitive.Description>
>(({ className, ...props }, ref) => (
  <DialogPrimitive.Description
    ref={ref}
    className={cn(
      "comet-body-s text-muted-foreground break-words whitespace-pre-wrap",
      className,
    )}
    {...props}
  />
));
DialogDescription.displayName = DialogPrimitive.Description.displayName;

export {
  Dialog,
  DialogPortal,
  DialogOverlay,
  DialogClose,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogAutoScrollBody,
  DialogFooter,
  DialogTitle,
  DialogDescription,
};
