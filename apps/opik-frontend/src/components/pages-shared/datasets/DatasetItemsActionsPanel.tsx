import React, { useCallback, useRef, useState } from "react";
import { Trash, Sparkles, Tag } from "lucide-react";
import get from "lodash/get";
import slugify from "slugify";

import { Button } from "@/components/ui/button";
import { DatasetItem, DATASET_TYPE, Evaluator } from "@/types/datasets";
import useDatasetItemBatchDeleteMutation from "@/api/datasets/useDatasetItemBatchDeleteMutation";
import ExportToButton from "@/components/shared/ExportToButton/ExportToButton";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DatasetExpansionDialog from "./DatasetExpansionDialog";
import GeneratedSamplesDialog from "./GeneratedSamplesDialog";
import AddTagDialog from "./AddTagDialog";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";
import { stripColumnPrefix, generateBatchGroupId } from "@/lib/utils";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { Filters } from "@/types/filters";
import {
  useBulkDeleteItems,
  useBulkAddItems,
  useIsAllItemsSelected,
} from "@/store/EvaluationSuiteDraftStore";
import { useToast } from "@/components/ui/use-toast";
import { DATASET_ITEM_SOURCE } from "@/types/datasets";
import { buildLLMJudgeBEConfig } from "@/lib/evaluator-converters";

type DatasetItemsActionsPanelProps = {
  getDataForExport: () => Promise<DatasetItem[]>;
  selectedDatasetItems: DatasetItem[];
  datasetId: string;
  datasetName: string;
  columnsToExport: string[];
  dynamicColumns: string[];
  filters?: Filters;
  search?: string;
  totalCount?: number;
  isDraftMode?: boolean;
  datasetType?: DATASET_TYPE;
  suiteEvaluators?: Evaluator[];
};

const DatasetItemsActionsPanel: React.FunctionComponent<
  DatasetItemsActionsPanelProps
> = ({
  getDataForExport,
  selectedDatasetItems,
  datasetId,
  datasetName,
  columnsToExport,
  dynamicColumns,
  filters = [],
  search = "",
  totalCount = 0,
  isDraftMode = false,
  datasetType,
  suiteEvaluators,
}) => {
  const resetKeyRef = useRef(0);
  const [expansionDialogOpen, setExpansionDialogOpen] =
    useState<boolean>(false);
  const [generatedSamplesDialogOpen, setGeneratedSamplesDialogOpen] =
    useState<boolean>(false);
  const [generatedSamples, setGeneratedSamples] = useState<DatasetItem[]>([]);
  const [addTagDialogOpen, setAddTagDialogOpen] = useState<boolean>(false);
  const disabled = !selectedDatasetItems?.length;

  const { mutate } = useDatasetItemBatchDeleteMutation();
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);
  const bulkDeleteItems = useBulkDeleteItems();
  const bulkAddItems = useBulkAddItems();
  const isAllItemsSelected = useIsAllItemsSelected();
  const { toast } = useToast();

  const deleteDatasetItemsHandler = useCallback(() => {
    if (!isAllItemsSelected) {
      // Use draft store for specific IDs
      const ids = selectedDatasetItems.map((i) => i.id);
      bulkDeleteItems(ids);
    } else {
      // Use API for filter-based deletion
      mutate({
        datasetId,
        ids: selectedDatasetItems.map((i) => i.id),
        isAllItemsSelected,
        filters,
        search,
        batchGroupId: isAllItemsSelected ? generateBatchGroupId() : undefined,
      });
    }
  }, [
    datasetId,
    selectedDatasetItems,
    mutate,
    isAllItemsSelected,
    filters,
    search,
    bulkDeleteItems,
  ]);

  const handleSamplesGenerated = useCallback((samples: DatasetItem[]) => {
    setGeneratedSamples(samples);
    setGeneratedSamplesDialogOpen(true);
  }, []);

  const handleAddGeneratedItems = useCallback(
    (items: DatasetItem[]) => {
      const now = new Date().toISOString();
      const itemsToAdd = items.map((item) => {
        const rawData = item.data as Record<string, unknown>;
        const { _opik_description, _opik_evaluator_assertions, ...cleanData } =
          rawData;

        const assertions = Array.isArray(_opik_evaluator_assertions)
          ? _opik_evaluator_assertions.filter(
              (a): a is string => typeof a === "string" && a.trim().length > 0,
            )
          : [];

        const evaluators: Evaluator[] | undefined =
          assertions.length > 0
            ? [
                {
                  name: "LLM Judge",
                  type: "llm_judge",
                  config: buildLLMJudgeBEConfig({ assertions }),
                },
              ]
            : undefined;

        return {
          data: cleanData,
          description:
            typeof _opik_description === "string"
              ? _opik_description
              : undefined,
          evaluators,
          source: DATASET_ITEM_SOURCE.manual,
          tags: item.tags || [],
          created_at: now,
          last_updated_at: now,
        };
      });

      bulkAddItems(itemsToAdd);

      const plural = items.length !== 1 ? "s" : "";
      toast({
        title: "Samples added to draft",
        description: `${items.length} sample${plural} added to your draft changes`,
      });
    },
    [bulkAddItems, toast],
  );

  const mapRowData = useCallback(async () => {
    const datasetItems = await getDataForExport();
    return datasetItems.map((item) => {
      return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
        let key = column;
        let value: unknown;

        if (dynamicColumns.includes(column)) {
          const columnName = stripColumnPrefix(
            column,
            DATASET_ITEM_DATA_PREFIX,
          );
          key = columnName;
          value = get(item.data, columnName, "");
        } else if (column === "expected_behaviors") {
          key = "evaluators";
          value = item.evaluators;
        } else {
          value = get(item, column, "");
        }

        // Normalize values for export
        if (value === null || value === undefined) {
          value = "";
        } else if (Array.isArray(value) && value.length === 0) {
          value = "";
        } else if (typeof value === "object") {
          value = JSON.stringify(value);
        }

        acc[key] = value;
        return acc;
      }, {});
    });
  }, [getDataForExport, columnsToExport, dynamicColumns]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(datasetName, {
        lower: true,
      })}-evaluation-suite-items.${extension}`;
    },
    [datasetName],
  );

  return (
    <div className="flex items-center gap-2">
      <DatasetExpansionDialog
        key={`dataset-expansion-${resetKeyRef.current}`}
        datasetId={datasetId}
        open={expansionDialogOpen}
        setOpen={setExpansionDialogOpen}
        onSamplesGenerated={handleSamplesGenerated}
        datasetType={datasetType}
        suiteEvaluators={suiteEvaluators}
      />

      <GeneratedSamplesDialog
        key={`generate-samples-${resetKeyRef.current}`}
        samples={generatedSamples}
        open={generatedSamplesDialogOpen}
        setOpen={setGeneratedSamplesDialogOpen}
        onAddItems={handleAddGeneratedItems}
      />

      <AddTagDialog
        key={`tag-${resetKeyRef.current}`}
        datasetId={datasetId}
        rows={selectedDatasetItems}
        open={addTagDialogOpen}
        setOpen={setAddTagDialogOpen}
        onSuccess={() => {}}
        filters={filters}
        search={search}
        totalCount={totalCount}
      />

      <Button
        variant="secondary"
        size="sm"
        onClick={() => {
          setExpansionDialogOpen(true);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      >
        <Sparkles className="mr-2 size-4" />
        Expand with AI
      </Button>

      <TooltipWrapper content="Manage tags">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setAddTagDialogOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Tag />
        </Button>
      </TooltipWrapper>

      <ExportToButton
        disabled={
          disabled ||
          columnsToExport.length === 0 ||
          !isExportEnabled ||
          isDraftMode
        }
        getData={mapRowData}
        generateFileName={generateFileName}
        tooltipContent={
          !isExportEnabled
            ? "Export functionality is disabled for this installation"
            : undefined
        }
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={deleteDatasetItemsHandler}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default DatasetItemsActionsPanel;
