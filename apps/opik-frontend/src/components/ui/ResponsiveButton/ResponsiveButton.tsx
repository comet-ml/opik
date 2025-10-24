import React from "react";
import { Button, ButtonProps } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useResponsiveToolbar } from "@/contexts/ResponsiveToolbarContext";

export interface ResponsiveButtonProps extends Omit<ButtonProps, "size"> {
  text: string;
  icon?: React.ReactNode;
  size?: "default" | "sm" | "xs" | "2xs" | "3xs" | "lg";
  iconSize?: "default" | "sm" | "xs" | "2xs" | "3xs" | "lg";
}

export const ResponsiveButton: React.FC<ResponsiveButtonProps> = ({
  text,
  icon,
  size = "sm",
  iconSize = "sm",
  className,
  children,
  ...buttonProps
}) => {
  const { hasSpace } = useResponsiveToolbar();

  const iconSizeMap: Record<string, string> = {
    default: "icon",
    sm: "icon-sm",
    xs: "icon-xs",
    "2xs": "icon-2xs",
    "3xs": "icon-3xs",
    lg: "icon-lg",
  };

  const iconMarginMap: Record<string, string> = {
    default: "mr-2",
    sm: "mr-1.5",
    xs: "mr-1",
    "2xs": "mr-1",
    "3xs": "mr-0.5",
    lg: "mr-2.5",
  };

  const iconOnlySize = iconSizeMap[iconSize] as ButtonProps["size"];
  const iconMargin = iconMarginMap[size];

  if (hasSpace) {
    // Full button with text and icon
    return (
      <Button
        size={size}
        className={cn("shrink-0", className)}
        {...buttonProps}
      >
        {icon && <span className={cn(iconMargin, "size-3.5")}>{icon}</span>}
        {text}
        {children}
      </Button>
    );
  }

  // Compact icon-only button with tooltip
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          size={iconOnlySize}
          className={cn("shrink-0", className)}
          {...buttonProps}
        >
          {icon}
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{text}</TooltipContent>
    </Tooltip>
  );
};

