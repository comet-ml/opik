import React from "react";

import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import { usePermissions } from "@/contexts/PermissionsContext";

type DatasetItemsEmptyContentProps = {
  title: string;
  itemName: string;
  hasDeletedItemsInDraft: boolean;
  onAddItem: () => void;
};

const DatasetItemsEmptyContent: React.FunctionComponent<
  DatasetItemsEmptyContentProps
> = ({ title, itemName, hasDeletedItemsInDraft, onAddItem }) => {
  const {
    permissions: { canEditDatasets },
  } = usePermissions();

  let description = "";
  if (!hasDeletedItemsInDraft) {
    description = canEditDatasets
      ? `Add ${itemName}s to run evaluations and measure performance.`
      : `Users with edit access can add ${itemName}s.`;
  }

  return (
    <DataTableEmptyContent title={title} description={description}>
      {canEditDatasets && !hasDeletedItemsInDraft && (
        <button
          onClick={onAddItem}
          className="comet-body-s underline underline-offset-4 hover:text-primary"
          data-testid="dataset-items-empty-add-button"
        >
          Add new {itemName}
        </button>
      )}
    </DataTableEmptyContent>
  );
};

export default DatasetItemsEmptyContent;
