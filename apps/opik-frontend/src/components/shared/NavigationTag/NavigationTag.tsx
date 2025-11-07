import React from "react";
import ResourceLink, {
  RESOURCE_TYPE,
  RESOURCE_MAP,
} from "@/components/shared/ResourceLink/ResourceLink";

type NavigationTagProps = {
  id: string;
  name?: string;
  resource: RESOURCE_TYPE;
  search?: Record<string, string | number | string[]>;
  params?: Record<string, string | number | string[]>;
  isDeleted?: boolean;
};

const NavigationTag: React.FunctionComponent<NavigationTagProps> = ({
  id,
  name,
  resource,
  search,
  params,
  isDeleted = false,
}) => {
  const resourceLabel = RESOURCE_MAP[resource].label;
  const tooltipContent = name
    ? `Navigate to ${resourceLabel}: ${name}`
    : undefined;

  return (
    <ResourceLink
      id={id}
      name={name}
      resource={resource}
      search={search}
      params={params}
      isDeleted={isDeleted}
      tooltipContent={tooltipContent}
      asTag
    />
  );
};

export default NavigationTag;
