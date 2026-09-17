import React, { useMemo, useState } from "react";
import { MessageCircleWarning, Plus } from "lucide-react";

import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { DropdownOption } from "@/types/shared";
import { Alert, AlertDescription } from "@/ui/alert";
import { Label } from "@/ui/label";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { usePermissions } from "@/contexts/PermissionsContext";
import LoadableSelectBox from "@/v2/components/LoadableSelectBox/LoadableSelectBox";
import { Separator } from "@/ui/separator";
import { ListAction } from "@/ui/list-action";
import { ADD_TO_DATASET_TYPE_CONFIG } from "./addToDatasetConfig";
import type useAddToDatasetForm from "./useAddToDatasetForm";
import { DATASETS_PAGE_SIZE } from "./useAddToDatasetForm";

type CreateDialogRenderProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated: (dataset: Dataset) => void;
};

type DatasetPickerSectionProps = {
  datasetType: DATASET_TYPE;
  form: ReturnType<typeof useAddToDatasetForm>;
  renderCreateDialog: (props: CreateDialogRenderProps) => React.ReactNode;
};

const DatasetPickerSection: React.FunctionComponent<
  DatasetPickerSectionProps
> = ({ datasetType, form, renderCreateDialog }) => {
  const {
    entityName,
    icon: EntityIcon,
    noSelectionExplainerId,
    emptyStateDescription,
    supportsFieldMapping,
  } = ADD_TO_DATASET_TYPE_CONFIG[datasetType];

  const {
    selectedDataset,
    handleDatasetCreated,
    handleDatasetSelect,
    datasetOptions,
    isPending,
    noValidRows,
    partialValid,
  } = form;

  const [openCreateDialog, setOpenCreateDialog] = useState<boolean>(false);

  const {
    permissions: { canCreateDatasets },
  } = usePermissions();

  const emptyDropdownState = useMemo(
    () => (
      <div className="flex min-h-32 flex-col items-center justify-center gap-1 px-6 py-4 text-center">
        <EntityIcon className="mb-1 size-5 text-muted-slate" />
        <span className="comet-body-s-accented">No {entityName}s yet</span>
        <span className="comet-body-xs text-muted-slate">
          {emptyStateDescription}
        </span>
      </div>
    ),
    [EntityIcon, entityName, emptyStateDescription],
  );

  const renderAlert = () => {
    const missingInputHint = supportsFieldMapping
      ? " Turn on advanced mapping to pick the fields to use instead."
      : "";
    const text = noValidRows
      ? `There are no rows that can be added as ${entityName} items. The input field is missing.${missingInputHint}`
      : `Only rows with input fields will be added as ${entityName} items.`;

    if (noValidRows || partialValid) {
      return (
        <Alert className="mt-4">
          <MessageCircleWarning />
          <AlertDescription>{text}</AlertDescription>
        </Alert>
      );
    }

    return null;
  };

  return (
    <>
      {!selectedDataset && (
        <ExplainerDescription
          className="mb-4"
          {...EXPLAINERS_MAP[noSelectionExplainerId]}
        />
      )}
      <div className="my-2">
        <Label className="comet-body-s-accented mb-1">
          Select a {entityName}
        </Label>
        <LoadableSelectBox
          value={selectedDataset?.id ?? ""}
          onChange={handleDatasetSelect}
          options={datasetOptions}
          placeholder={
            <div className="flex items-center gap-2">
              <EntityIcon className="size-4 shrink-0 text-muted-slate" />
              <span>Select a {entityName}</span>
            </div>
          }
          renderTitle={(option: DropdownOption<string>) => (
            <div className="flex items-center gap-2 truncate">
              <EntityIcon className="size-4 shrink-0 text-muted-slate" />
              <span className="truncate">{option.label}</span>
            </div>
          )}
          searchPlaceholder={`Search ${entityName}s`}
          isLoading={isPending}
          disabled={noValidRows && !supportsFieldMapping}
          buttonClassName="w-full"
          optionsCount={DATASETS_PAGE_SIZE}
          emptyState={emptyDropdownState}
          actionPanel={
            canCreateDatasets ? (
              <>
                <Separator className="-mx-px my-1 bg-muted" />
                <ListAction
                  variant="default"
                  size="sm"
                  onClick={() => setOpenCreateDialog(true)}
                >
                  <Plus className="size-3.5 shrink-0" />
                  Add {entityName}
                </ListAction>
              </>
            ) : undefined
          }
        />
      </div>
      {renderAlert()}
      {renderCreateDialog({
        open: openCreateDialog,
        setOpen: setOpenCreateDialog,
        onDatasetCreated: handleDatasetCreated,
      })}
    </>
  );
};

export default DatasetPickerSection;
