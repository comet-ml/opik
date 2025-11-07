import React from "react";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

const RESOURCE_TYPE_LABELS = {
  [RESOURCE_TYPE.project]: "project",
  [RESOURCE_TYPE.dataset]: "dataset",
  [RESOURCE_TYPE.prompt]: "prompt",
  [RESOURCE_TYPE.experiment]: "experiment",
  [RESOURCE_TYPE.optimization]: "optimization",
  [RESOURCE_TYPE.trial]: "trial",
  [RESOURCE_TYPE.annotationQueue]: "annotation queue",
};

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
  const resourceTypeLabel = RESOURCE_TYPE_LABELS[resource];
  const tooltipContent = name
    ? `Navigate to ${resourceTypeLabel}: ${name}`
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

