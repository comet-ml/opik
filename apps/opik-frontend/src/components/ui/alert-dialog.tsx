import * as React from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogFooter,
  DialogTitle,
  DialogDescription,
} from "./dialog";
import { Button } from "./button";
import { cn } from "@/lib/utils";

interface AlertDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  children?: React.ReactNode;
}

const AlertDialog: React.FC<AlertDialogProps> = ({ open, onOpenChange, children }) => {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {children}
    </Dialog>
  );
};

const AlertDialogContent = React.forwardRef<
  HTMLDivElement,
  React.HTMLAttributes<HTMLDivElement>
>(({ className, children, ...props }, ref) => (
  <DialogContent className={cn("sm:max-w-[425px]", className)} {...props}>
    {children}
  </DialogContent>
));
AlertDialogContent.displayName = "AlertDialogContent";

const AlertDialogHeader: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({ className, ...props }) => (
  <DialogHeader className={className} {...props} />
);
AlertDialogHeader.displayName = "AlertDialogHeader";

const AlertDialogFooter: React.FC<React.HTMLAttributes<HTMLDivElement>> = ({ className, ...props }) => (
  <DialogFooter className={className} {...props} />
);
AlertDialogFooter.displayName = "AlertDialogFooter";

const AlertDialogTitle: React.FC<React.HTMLAttributes<HTMLHeadingElement>> = ({ className, ...props }) => (
  <DialogTitle className={className} {...props} />
);
AlertDialogTitle.displayName = "AlertDialogTitle";

const AlertDialogDescription: React.FC<React.HTMLAttributes<HTMLParagraphElement>> = ({ className, ...props }) => (
  <DialogDescription className={className} {...props} />
);
AlertDialogDescription.displayName = "AlertDialogDescription";

interface AlertDialogActionProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "default" | "destructive" | "outline" | "secondary" | "ghost" | "link";
}

const AlertDialogAction = React.forwardRef<HTMLButtonElement, AlertDialogActionProps>(
  ({ className, variant = "default", ...props }, ref) => (
    <Button className={className} variant={variant} ref={ref} {...props} />
  )
);
AlertDialogAction.displayName = "AlertDialogAction";

const AlertDialogCancel = React.forwardRef<HTMLButtonElement, AlertDialogActionProps>(
  ({ className, variant = "outline", ...props }, ref) => (
    <Button className={className} variant={variant} ref={ref} {...props} />
  )
);
AlertDialogCancel.displayName = "AlertDialogCancel";

export {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogFooter,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogAction,
  AlertDialogCancel,
}; 