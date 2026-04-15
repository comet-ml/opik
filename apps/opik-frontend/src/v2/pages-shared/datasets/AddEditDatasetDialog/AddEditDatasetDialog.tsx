import React from "react";

import { Dataset, DATASET_TYPE } from "@/types/datasets";
import useDatasetForm from "./useDatasetForm";
import AddEditDatasetDialogWrapper from "./AddEditDatasetDialogWrapper";

type AddEditDatasetDialogProps = {
  dataset?: Dataset;
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
  hideUpload?: boolean;
  csvRequired?: boolean;
};

const AddEditDatasetDialog: React.FunctionComponent<
  AddEditDatasetDialogProps
> = ({ dataset, open, setOpen, onDatasetCreated, hideUpload, csvRequired }) => {
  const form = useDatasetForm({
    dataset,
    open,
    setOpen,
    onDatasetCreated,
    hideUpload,
    csvRequired,
    skipEvaluationCriteria: true,
    datasetType: DATASET_TYPE.DATASET,
  });

  return (
    <AddEditDatasetDialogWrapper
      open={open}
      setOpen={setOpen}
      form={form}
      hideUpload={hideUpload}
      idPrefix="dataset"
    />
  );
};

export default AddEditDatasetDialog;
