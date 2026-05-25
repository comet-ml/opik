import React from "react";
import { PopoverAnchor } from "@/ui/popover";
import { useAutocompleteContext } from "./AutocompleteContext";

interface AutocompleteAnchorProps {
  children: React.ReactElement;
}

export const AutocompleteAnchor: React.FC<AutocompleteAnchorProps> = ({
  children,
}) => {
  const { inputProps } = useAutocompleteContext();
  const child = React.Children.only(children);
  const merged = { ...(child.props as object), ...inputProps };
  return (
    <PopoverAnchor asChild>{React.cloneElement(child, merged)}</PopoverAnchor>
  );
};
