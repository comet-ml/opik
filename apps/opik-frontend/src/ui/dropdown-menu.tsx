import * as React from "react";
import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import { cva, type VariantProps } from "class-variance-authority";
import { Check, ChevronRight, Circle } from "lucide-react";

import { Checkbox } from "@/ui/checkbox";
import { cn } from "@/lib/utils";
import { usePortalContainer } from "@/lib/portal-container";

const dropdownMenuItemVariants = cva(
  "comet-body-s relative flex cursor-pointer select-none items-center outline-none transition-colors data-[disabled]:pointer-events-none data-[disabled]:bg-muted-disabled data-[selected]:bg-primary-100 data-[disabled]:text-muted-gray data-[selected]:text-primary data-[selected]:focus:bg-secondary data-[selected]:focus:text-primary",
  {
    variants: {
      variant: {
        default: "focus:bg-primary-foreground focus:text-foreground",
        destructive:
          "text-destructive focus:bg-destructive/10 focus:text-destructive",
      },
      size: {
        default: "rounded-sm px-4 py-2",
        sm: "h-8 rounded-md px-3",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

type DropdownMenuItemVariants = VariantProps<typeof dropdownMenuItemVariants>;

const dropdownMenuLabelVariants = cva("comet-body-s-accented", {
  variants: {
    size: {
      default: "min-h-10 px-4 py-2.5",
      sm: "flex h-8 items-center px-3",
    },
  },
  defaultVariants: { size: "default" },
});

type DropdownMenuLabelVariants = VariantProps<typeof dropdownMenuLabelVariants>;

const dropdownMenuSubTriggerVariants = cva(
  "flex cursor-default select-none items-center outline-none focus:bg-primary-foreground data-[state=open]:bg-primary-foreground",
  {
    variants: {
      variant: {
        default: "text-sm",
        menu: "comet-body-s-accented text-foreground [&>svg]:size-3.5 [&>svg]:text-light-slate",
      },
      size: {
        default: "rounded-sm px-4 py-2",
        sm: "h-8 rounded-md px-3",
      },
    },
    defaultVariants: { variant: "default", size: "default" },
  },
);

type DropdownMenuSubTriggerVariants = VariantProps<
  typeof dropdownMenuSubTriggerVariants
>;

const DropdownMenu = DropdownMenuPrimitive.Root;

const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;

const DropdownMenuGroup = DropdownMenuPrimitive.Group;

const DropdownMenuPortal: React.FC<
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Portal>
> = ({ children, ...props }) => {
  const container = usePortalContainer();
  return (
    <DropdownMenuPrimitive.Portal container={container} {...props}>
      {children}
    </DropdownMenuPrimitive.Portal>
  );
};

const DropdownMenuSub = DropdownMenuPrimitive.Sub;

const DropdownMenuRadioGroup = DropdownMenuPrimitive.RadioGroup;

const DropdownMenuSubTrigger = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.SubTrigger>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.SubTrigger> &
    DropdownMenuSubTriggerVariants & {
      inset?: boolean;
    }
>(({ className, inset, variant, size, children, ...props }, ref) => (
  <DropdownMenuPrimitive.SubTrigger
    ref={ref}
    className={cn(
      dropdownMenuSubTriggerVariants({ variant, size }),
      inset && "pl-8",
      className,
    )}
    {...props}
  >
    {children}
    <ChevronRight className="ml-auto size-4" />
  </DropdownMenuPrimitive.SubTrigger>
));
DropdownMenuSubTrigger.displayName =
  DropdownMenuPrimitive.SubTrigger.displayName;

const DropdownMenuSubContent = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.SubContent>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.SubContent>
>(({ className, ...props }, ref) => {
  const container = usePortalContainer();
  return (
    <DropdownMenuPrimitive.SubContent
      ref={ref}
      collisionBoundary={container ?? undefined}
      className={cn(
        "z-50 min-w-[8rem] overflow-hidden rounded-md border bg-background p-1 text-foreground shadow-lg data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
        className,
      )}
      {...props}
    />
  );
});
DropdownMenuSubContent.displayName =
  DropdownMenuPrimitive.SubContent.displayName;

const DropdownMenuContent = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>
>(({ className, sideOffset = 4, ...props }, ref) => {
  const container = usePortalContainer();
  return (
    <DropdownMenuPrimitive.Portal container={container}>
      <DropdownMenuPrimitive.Content
        ref={ref}
        sideOffset={sideOffset}
        collisionBoundary={container ?? undefined}
        className={cn(
          "z-50 min-w-[8rem] overflow-hidden rounded-md border bg-background p-1 text-foreground shadow-md data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2",
          className,
        )}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  );
});
DropdownMenuContent.displayName = DropdownMenuPrimitive.Content.displayName;

const DropdownMenuItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item> &
    DropdownMenuItemVariants & {
      inset?: boolean;
      selected?: boolean;
    }
>(({ className, inset, variant, size, selected, ...props }, ref) => (
  <DropdownMenuPrimitive.Item
    ref={ref}
    data-selected={selected ? "" : undefined}
    className={cn(
      dropdownMenuItemVariants({ variant, size }),
      inset && "pl-8",
      className,
    )}
    {...props}
  />
));
DropdownMenuItem.displayName = DropdownMenuPrimitive.Item.displayName;

const DropdownMenuCheckboxItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.CheckboxItem>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.CheckboxItem>
>(({ className, children, checked, ...props }, ref) => (
  <DropdownMenuPrimitive.CheckboxItem
    ref={ref}
    className={cn(
      "comet-body-s relative flex cursor-pointer select-none items-center rounded-sm py-1.5 pl-8 pr-2 outline-none transition-colors focus:bg-primary-foreground focus:text-foreground data-[disabled]:pointer-events-none data-[disabled]:bg-muted-disabled data-[disabled]:text-muted-gray",
      className,
    )}
    checked={checked}
    {...props}
  >
    <span className="absolute left-2 flex size-3.5 items-center justify-center">
      <DropdownMenuPrimitive.ItemIndicator>
        <Check className="size-4" />
      </DropdownMenuPrimitive.ItemIndicator>
    </span>
    {children}
  </DropdownMenuPrimitive.CheckboxItem>
));
DropdownMenuCheckboxItem.displayName =
  DropdownMenuPrimitive.CheckboxItem.displayName;

const DropdownMenuCustomCheckboxItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.CheckboxItem>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.CheckboxItem>
>(({ className, children, checked, disabled, ...props }, ref) => (
  <DropdownMenuPrimitive.CheckboxItem
    ref={ref}
    className={cn(
      "comet-body-s relative flex cursor-pointer min-h-10 select-none items-center rounded-sm pl-10 pr-4 outline-none transition-colors focus:bg-primary-foreground focus:text-foreground data-[disabled]:pointer-events-none data-[disabled]:bg-muted-disabled data-[disabled]:text-muted-gray break-all",
      className,
    )}
    checked={checked}
    disabled={disabled}
    {...props}
  >
    <span className="absolute left-2 flex size-8 items-center justify-center">
      <Checkbox checked={checked} disabled={disabled}></Checkbox>
    </span>
    {children}
  </DropdownMenuPrimitive.CheckboxItem>
));
DropdownMenuCustomCheckboxItem.displayName = "DropdownMenuCustomCheckboxItem";

const DropdownMenuRadioItem = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.RadioItem>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.RadioItem>
>(({ className, children, ...props }, ref) => (
  <DropdownMenuPrimitive.RadioItem
    ref={ref}
    className={cn(
      "comet-body-s relative flex cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 outline-none transition-colors focus:bg-primary-foreground focus:text-foreground data-[disabled]:pointer-events-none data-[disabled]:bg-muted-disabled data-[disabled]:text-muted-gray",
      className,
    )}
    {...props}
  >
    <span className="absolute left-2 flex size-3.5 items-center justify-center">
      <DropdownMenuPrimitive.ItemIndicator>
        <Circle className="size-2 fill-current" />
      </DropdownMenuPrimitive.ItemIndicator>
    </span>
    {children}
  </DropdownMenuPrimitive.RadioItem>
));
DropdownMenuRadioItem.displayName = DropdownMenuPrimitive.RadioItem.displayName;

const DropdownMenuLabel = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Label>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label> &
    DropdownMenuLabelVariants & {
      inset?: boolean;
    }
>(({ className, inset, size, ...props }, ref) => (
  <DropdownMenuPrimitive.Label
    ref={ref}
    className={cn(
      dropdownMenuLabelVariants({ size }),
      inset && "pl-8",
      className,
    )}
    {...props}
  />
));
DropdownMenuLabel.displayName = DropdownMenuPrimitive.Label.displayName;

const DropdownMenuSeparator = React.forwardRef<
  React.ElementRef<typeof DropdownMenuPrimitive.Separator>,
  React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Separator>
>(({ className, ...props }, ref) => (
  <DropdownMenuPrimitive.Separator
    ref={ref}
    className={cn("-mx-[1px] my-1 h-px bg-muted", className)}
    {...props}
  />
));
DropdownMenuSeparator.displayName = DropdownMenuPrimitive.Separator.displayName;

const DropdownMenuShortcut = ({
  className,
  ...props
}: React.HTMLAttributes<HTMLSpanElement>) => {
  return (
    <span
      className={cn("ml-auto text-xs tracking-widest opacity-60", className)}
      {...props}
    />
  );
};
DropdownMenuShortcut.displayName = "DropdownMenuShortcut";

export {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuCheckboxItem,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuRadioItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuGroup,
  DropdownMenuPortal,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuRadioGroup,
};
