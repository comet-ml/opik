import * as React from "react";
import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import { ChevronDown } from "lucide-react";

import { Button, ButtonProps } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// Root component that manages dropdown state
const ButtonWithDropdown = DropdownMenuPrimitive.Root;

// Trigger component - renders as a split button
interface ButtonWithDropdownTriggerProps
  extends Omit<ButtonProps, "asChild">,
    Omit<
      React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Trigger>,
      "asChild"
    > {
  children: React.ReactNode;
  onPrimaryClick?: () => void;
}

// Dropdown trigger styles by variant (darker background when open)
const dropdownTriggerVariantStyles = {
  default:
    "data-[state=open]:bg-primary-active data-[state=open]:hover:bg-primary-active",
  outline:
    "data-[state=open]:bg-primary-foreground data-[state=open]:hover:bg-primary-foreground",
  secondary:
    "data-[state=open]:bg-secondary/80 data-[state=open]:hover:bg-secondary/80",
  destructive:
    "data-[state=open]:bg-destructive data-[state=open]:hover:bg-destructive",
} as const;

// Dropdown trigger padding by size
const dropdownTriggerSizeStyles = {
  sm: "px-1.5",
  default: "px-2",
  lg: "px-2.5",
} as const;

const ButtonWithDropdownTrigger = React.forwardRef<
  HTMLButtonElement,
  ButtonWithDropdownTriggerProps
>(
  (
    {
      className,
      variant = "default",
      size = "sm",
      children,
      onPrimaryClick,
      disabled,
    },
    ref,
  ) => {
    return (
      <div className="inline-flex">
        {/* Main button - executes primary action */}
        <Button
          ref={ref}
          variant={variant}
          size={size}
          onClick={onPrimaryClick}
          disabled={disabled}
          className={cn("rounded-r-none", className)}
        >
          {children}
        </Button>

        {/* Dropdown trigger - opens menu with darker background when open */}
        <DropdownMenuPrimitive.Trigger asChild>
          <Button
            variant={variant}
            size={size}
            disabled={disabled}
            className={cn(
              "rounded-l-none",
              dropdownTriggerVariantStyles[
                variant as keyof typeof dropdownTriggerVariantStyles
              ],
              dropdownTriggerSizeStyles[
                size as keyof typeof dropdownTriggerSizeStyles
              ],
            )}
            aria-label="Show more options"
          >
            <ChevronDown className="size-4" />
          </Button>
        </DropdownMenuPrimitive.Trigger>
      </div>
    );
  },
);
ButtonWithDropdownTrigger.displayName = "ButtonWithDropdownTrigger";

// Content component - dropdown menu container
const ButtonWithDropdownContent = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>
>(({ className, sideOffset = 4, ...props }, ref) => (
  <DropdownMenuPrimitive.Portal>
    <DropdownMenuPrimitive.Content
      ref={ref}
      sideOffset={sideOffset}
      className={cn(
        "z-50 min-w-[8rem] overflow-hidden rounded-md border bg-background p-1 text-foreground shadow-md data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
        className,
      )}
      {...props}
    />
  </DropdownMenuPrimitive.Portal>
));
ButtonWithDropdownContent.displayName = "ButtonWithDropdownContent";

// Item component - individual dropdown menu item
const ButtonWithDropdownItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item> & {
    inset?: boolean;
  }
>(({ className, inset, ...props }, ref) => (
  <DropdownMenuPrimitive.Item
    ref={ref}
    className={cn(
      "comet-body-s relative flex cursor-pointer select-none items-center rounded-sm px-4 py-2 outline-none transition-colors focus:bg-primary-foreground focus:text-foreground data-[disabled]:pointer-events-none data-[disabled]:bg-muted-disabled data-[disabled]:text-muted-gray",
      inset && "pl-8",
      className,
    )}
    {...props}
  />
));
ButtonWithDropdownItem.displayName = "ButtonWithDropdownItem";

// Separator component
const ButtonWithDropdownSeparator = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Separator>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Separator>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Separator
    ref={ref}
    className={cn("-mx-[1px] my-1 h-px bg-muted", className)}
    {...props}
  />
));
ButtonWithDropdownSeparator.displayName = "ButtonWithDropdownSeparator";

// Label component
const ButtonWithDropdownLabel = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Label>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label> & {
    inset?: boolean;
  }
>(({ className, inset, ...props }, ref) => (
  <DropdownMenuPrimitive.Label
    ref={ref}
    className={cn(
      "comet-body-s-accented min-h-10 px-4 py-2.5",
      inset && "pl-8",
      className,
    )}
    {...props}
  />
));
ButtonWithDropdownLabel.displayName = "ButtonWithDropdownLabel";

export {
  ButtonWithDropdown,
  ButtonWithDropdownTrigger,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
  ButtonWithDropdownSeparator,
  ButtonWithDropdownLabel,
};
