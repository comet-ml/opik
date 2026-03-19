import React from "react";

import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
  ResourceLinkProps,
} from "@/shared/ResourceLink/ResourceLink";
import { Filter } from "@/types/filters";
import { TagProps } from "@/ui/tag";

const DEFAULT_ICON_SIZE = 3;

type NavigationTagProps = {
  id: string;
  name: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[] | Filter[]>;
  tooltipContent?: string;
  className?: string;
  isSmall?: boolean;
  iconSize?: ResourceLinkProps["iconSize"];
  size?: TagProps["size"];
  variant?: TagProps["variant"];
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  tooltipContent,
  className,
  isSmall = false,
  iconSize = DEFAULT_ICON_SIZE,
  size = "md",
  variant = "transparent",
}) => {
  const resourceLabel = RESOURCE_MAP[resource].label;
  const defaultTooltipContent = `Navigate to ${resourceLabel}: ${name}`;

  return (
    <ResourceLink
      id={id}
      name={name}
      resource={resource}
      search={search}
      tooltipContent={tooltipContent ?? defaultTooltipContent}
      variant={variant}
      className={className}
      iconSize={iconSize}
      gapSize={1}
      asTag
      isSmall={isSmall}
      size={size}
    />
  );
};

export default NavigationTag;
