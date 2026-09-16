import React, { useRef, useState } from "react";

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
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import MorphDialog, { MorphMode } from "@/shared/MorphDialog/MorphDialog";
import DatasetPickerSection from "./DatasetPickerSection";
import FieldMappingSection from "./FieldMappingSection";
import MappingPreviewSection from "./MappingPreviewSection";
import useAddToDatasetForm, { EnrichmentOptions } from "./useAddToDatasetForm";
import useDatasetColumns from "./useDatasetColumns";
import useFieldMappings from "./useFieldMappings";
import useFieldCoverage from "./useFieldCoverage";
import useMappingPreview from "./useMappingPreview";
import useMappingSampleEntities from "./useMappingSampleEntities";

const fieldCountLabel = (count: number) =>
  `${count} ${count === 1 ? "field" : "fields"}`;

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
  const [advanced, setAdvanced] = useState(false);
  const morphMode: MorphMode = advanced ? "panel" : "modal";
  const submitExtrasRef = useRef<{ fieldMappings: Record<string, string> }>({
    fieldMappings: {},
  });

  const form = useAddToDatasetForm({
    selectedRows,
    open,
    setOpen,
    datasetType: DATASET_TYPE.DATASET,
    getSubmitExtras: () => submitExtrasRef.current,
  });

  const {
    hasOnlyTraces,
    hasOnlySpans,
    enrichmentOptions,
    setEnrichmentOptions,
    validTraces,
    validSpans,
    selectedDataset,
    noValidRows,
    fetching,
    configSectionRef,
    submit,
  } = form;

  const { columnNames, itemCount: datasetItemCount } = useDatasetColumns({
    datasetId: selectedDataset?.id,
    enabled: advanced,
  });

  const mappings = useFieldMappings({
    enrichmentOptions,
    setEnrichmentOptions,
    hasOnlySpans,
    datasetColumns: columnNames,
  });

  const { treeData, entities, isPending } = useMappingSampleEntities({
    validTraces,
    validSpans,
    hasOnlySpans,
    enabled: advanced && Boolean(selectedDataset),
  });

  const coverage = useFieldCoverage({
    fixedRows: mappings.fixedRows,
    customRows: mappings.customRows,
    managedRows: mappings.managedRows,
    selectedEntities: hasOnlySpans ? validSpans : validTraces,
    sampleEntities: entities,
  });

  const { columns, rows, emptyColumnCount } = useMappingPreview({
    fixedRows: mappings.fixedRows,
    customRows: mappings.customRows,
    managedRows: mappings.managedRows,
    rowErrors: mappings.rowErrors,
    entities,
  });

  submitExtrasRef.current = {
    fieldMappings: advanced ? mappings.fieldMappings : {},
  };

  const itemCount = hasOnlySpans ? validSpans.length : validTraces.length;

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

  const entityLabel = hasOnlySpans ? "spans" : "traces";

  const previewSummary =
    `Adding ${fieldCountLabel(columns.length)}` +
    (emptyColumnCount > 0
      ? ` · ${fieldCountLabel(emptyColumnCount)} ${
          emptyColumnCount === 1 ? "is" : "are"
        } empty for some ${entityLabel}`
      : "");

  const actions = (
    <div className="flex w-full items-center gap-4">
      {advanced && columns.length > 0 && (
        <span className="comet-body-xs truncate text-muted-slate">
          {previewSummary}
        </span>
      )}
      <div className="ml-auto flex items-center gap-2">
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
          disabled={
            !selectedDataset ||
            noValidRows ||
            fetching ||
            (advanced && !mappings.isValid)
          }
        >
          {itemCount > 0 ? `Add ${itemCount} items` : "Add to dataset"}
        </Button>
      </div>
    </div>
  );

  return (
    <MorphDialog
      open={open}
      onOpenChange={setOpen}
      mode={morphMode}
      panelId="add-to-dataset"
      title={
        <span className="flex items-center gap-2">
          Add to dataset
          <Tag variant="gray">
            {itemCount} {hasOnlySpans ? "spans" : "traces"}
          </Tag>
        </span>
      }
      footer={actions}
    >
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
          {!advanced &&
            hasOnlyTraces &&
            renderMetadataConfiguration("trace", true)}
          {!advanced && hasOnlySpans && renderMetadataConfiguration("span")}
          <FieldMappingSection
            advanced={advanced}
            setAdvanced={setAdvanced}
            treeData={treeData}
            hasOnlySpans={hasOnlySpans}
            coverage={coverage}
            datasetColumns={columnNames}
            datasetItemCount={datasetItemCount}
            mappings={mappings}
          />
          {advanced && (
            <MappingPreviewSection
              columns={columns}
              rows={rows}
              isPending={isPending}
              hasOnlySpans={hasOnlySpans}
            />
          )}
        </div>
      )}
    </MorphDialog>
  );
};

export default AddToDatasetDialog;
