import React from "react";

import { TEMPLATE_LIST } from "@/lib/dashboard/templates";
import { Dashboard } from "@/types/dashboard";

interface TriggerContentProps {
  selectedItem: (typeof TEMPLATE_LIST)[number] | Dashboard | null | undefined;
}

export const TriggerContent: React.FC<TriggerContentProps> = ({
  selectedItem,
}) => {
  if (!selectedItem) {
    return (
      <span className="truncate font-normal text-light-slate">
        Select a dashboard
      </span>
    );
  }

  return (
    <div className="flex min-w-0 items-center">
      <span className="shrink-0 text-muted-slate">Dashboard:</span>
      <span className="ml-1 truncate">
        {"name" in selectedItem
          ? selectedItem.name
          : "title" in selectedItem
            ? selectedItem.title
            : ""}
      </span>
    </div>
  );
};
