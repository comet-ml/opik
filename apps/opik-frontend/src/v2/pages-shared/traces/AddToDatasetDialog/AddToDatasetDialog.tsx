import React from "react";

import { Span, Trace } from "@/types/traces";
import { DATASET_TYPE } from "@/types/datasets";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { Checkbox } from "@/ui/checkbox";
import { Label } from "@/ui/label";
import AddEditDatasetDialog from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import DatasetPickerSection from "./DatasetPickerSection";
import useAddToDatasetForm, { EnrichmentOptions } from "./useAddToDatasetForm";

type AddToDatasetDialogProps = {
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToDatasetDialog: React.FunctionComponent<AddToDatasetDialogProps> = ({
  selectedRows,
  open,
  setOpen,
}) => {
  const form = useAddToDatasetForm({
    selectedRows,
    open,
    setOpen,
    datasetType: DATASET_TYPE.DATASET,
  });

  const {
    hasOnlyTraces,
    hasOnlySpans,
    enrichmentOptions,
    setEnrichmentOptions,
  } = form;

  const renderEnrichmentCheckbox = (
    id: string,
    label: string,
    checked: boolean,
    field: keyof EnrichmentOptions,
  ) => (
    <div className="flex items-center space-x-2">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(checked) =>
          setEnrichmentOptions((prev) => ({
            ...prev,
            [field]: checked === true,
          }))
        }
      />
      <Label htmlFor={id} className="comet-body-s cursor-pointer font-normal">
        {label}
      </Label>
    </div>
  );

  const renderMetadataConfiguration = (
    type: "trace" | "span",
    includeNestedSpans: boolean = false,
  ) => (
    <Accordion
      type="single"
      collapsible
      defaultValue="metadata"
      className="mb-4"
    >
      <AccordionItem value="metadata" className="border-t">
        <AccordionTrigger>
          {type === "trace"
            ? "Trace metadata configuration"
            : "Span metadata configuration"}
        </AccordionTrigger>
        <AccordionContent className="px-3">
          <div className="grid grid-cols-2 gap-3">
            {includeNestedSpans &&
              renderEnrichmentCheckbox(
                "include-spans",
                "Nested spans",
                enrichmentOptions.includeSpans,
                "includeSpans",
              )}
            {renderEnrichmentCheckbox(
              `include-tags${type === "span" ? "-span" : ""}`,
              "Tags",
              enrichmentOptions.includeTags,
              "includeTags",
            )}
            {renderEnrichmentCheckbox(
              `include-feedback-scores${type === "span" ? "-span" : ""}`,
              "Feedback scores",
              enrichmentOptions.includeFeedbackScores,
              "includeFeedbackScores",
            )}
            {renderEnrichmentCheckbox(
              `include-comments${type === "span" ? "-span" : ""}`,
              "Comments",
              enrichmentOptions.includeComments,
              "includeComments",
            )}
            {renderEnrichmentCheckbox(
              `include-usage${type === "span" ? "-span" : ""}`,
              "Usage metrics",
              enrichmentOptions.includeUsage,
              "includeUsage",
            )}
            {renderEnrichmentCheckbox(
              `include-metadata${type === "span" ? "-span" : ""}`,
              "Metadata",
              enrichmentOptions.includeMetadata,
              "includeMetadata",
            )}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );

  const { selectedDataset, noValidRows, fetching, configSectionRef, submit } =
    form;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>Add to dataset</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <DatasetPickerSection
            datasetType={DATASET_TYPE.DATASET}
            form={form}
            renderCreateDialog={({ open, setOpen, onDatasetCreated }) => (
              <AddEditDatasetDialog
                open={open}
                setOpen={setOpen}
                onDatasetCreated={onDatasetCreated}
                hideUpload={true}
              />
            )}
          />
          {selectedDataset && (
            <div ref={configSectionRef}>
              {hasOnlyTraces && renderMetadataConfiguration("trace", true)}
              {hasOnlySpans && renderMetadataConfiguration("span")}
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => setOpen(false)}
            disabled={fetching}
          >
            Cancel
          </Button>
          <Button
            onClick={() => {
              if (selectedDataset) submit(selectedDataset);
            }}
            disabled={!selectedDataset || noValidRows || fetching}
          >
            Add to dataset
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddToDatasetDialog;
