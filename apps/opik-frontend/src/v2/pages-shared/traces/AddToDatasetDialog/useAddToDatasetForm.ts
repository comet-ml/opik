import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import isUndefined from "lodash/isUndefined";
import { keepPreviousData } from "@tanstack/react-query";

import { Span, Trace } from "@/types/traces";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useAddTracesToDatasetMutation from "@/api/datasets/useAddTracesToDatasetMutation";
import useAddSpansToDatasetMutation from "@/api/datasets/useAddSpansToDatasetMutation";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { COLUMN_TYPE, DropdownOption } from "@/types/shared";
import { Filter } from "@/types/filters";
import { isObjectSpan } from "@/lib/traces";
import { useToast } from "@/ui/use-toast";
import { EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { ADD_TO_DATASET_TYPE_CONFIG } from "./addToDatasetConfig";

export const DATASETS_PAGE_SIZE = 100;

export type EnrichmentOptions = {
  includeSpans: boolean;
  includeTags: boolean;
  includeFeedbackScores: boolean;
  includeComments: boolean;
  includeUsage: boolean;
  includeMetadata: boolean;
};

type UseAddToDatasetFormParams = {
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetType: DATASET_TYPE;
  getSubmitExtras?: () => Record<string, unknown>;
  onDatasetChange?: () => void;
};

const useAddToDatasetForm = ({
  selectedRows,
  open,
  setOpen,
  datasetType,
  getSubmitExtras,
  onDatasetChange,
}: UseAddToDatasetFormParams) => {
  const { entityName, successExplainerId } =
    ADD_TO_DATASET_TYPE_CONFIG[datasetType];

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const { toast } = useToast();

  const [fetching, setFetching] = useState<boolean>(false);
  const [selectedDataset, setSelectedDataset] = useState<Dataset | null>(null);
  const configSectionRef = useRef<HTMLDivElement>(null);

  const [enrichmentOptions, setEnrichmentOptions] = useState<EnrichmentOptions>(
    {
      includeSpans: true,
      includeTags: true,
      includeFeedbackScores: true,
      includeComments: true,
      includeUsage: true,
      includeMetadata: true,
    },
  );

  const { mutate: addTracesToDataset } = useAddTracesToDatasetMutation();
  const { mutate: addSpansToDataset } = useAddSpansToDatasetMutation();

  const typeFilter = useMemo(
    () =>
      [
        {
          id: "type",
          field: "type",
          type: COLUMN_TYPE.string,
          operator: "=" as const,
          value: datasetType,
        },
      ] as Filter[],
    [datasetType],
  );

  const { data, isPending } = useProjectDatasetsList(
    {
      projectId: activeProjectId!,
      page: 1,
      size: DATASETS_PAGE_SIZE,
      filters: typeFilter,
    },
    {
      placeholderData: keepPreviousData,
      enabled: !!activeProjectId && open,
    },
  );

  const datasets = useMemo(() => data?.content ?? [], [data?.content]);

  const datasetOptions: DropdownOption<string>[] = useMemo(
    () =>
      datasets.map((d) => ({
        value: d.id,
        label: d.name,
        description: d.description,
      })),
    [datasets],
  );

  const datasetsById = useMemo(() => {
    const map = new Map<string, Dataset>();
    datasets.forEach((d) => map.set(d.id, d));
    return map;
  }, [datasets]);

  useEffect(() => {
    if (!isPending && datasets.length === 1 && !selectedDataset) {
      setSelectedDataset(datasets[0]);
    }
  }, [isPending, datasets, selectedDataset]);

  const validRows = useMemo(() => {
    return selectedRows.filter((r) => !isUndefined(r.input));
  }, [selectedRows]);

  const validTraces = useMemo(() => {
    return validRows.filter((r) => !isObjectSpan(r));
  }, [validRows]);

  const validSpans = useMemo(() => {
    return validRows.filter((r) => isObjectSpan(r));
  }, [validRows]);

  const hasOnlyTraces = validTraces.length > 0 && validSpans.length === 0;
  const hasOnlySpans = validSpans.length > 0 && validTraces.length === 0;

  const noValidRows = validRows.length === 0;
  const partialValid = validRows.length !== selectedRows.length;

  const onItemsAdded = useCallback(
    (hasTraces: boolean, hasSpans: boolean) => {
      let itemType = "Items";
      if (hasTraces && !hasSpans) {
        itemType = "Traces";
      } else if (hasSpans && !hasTraces) {
        itemType = "Spans";
      }

      toast({
        title: `${itemType} added to ${entityName}`,
        description: EXPLAINERS_MAP[successExplainerId].description,
      });
    },
    [toast, entityName, successExplainerId],
  );

  const submit = useCallback(
    (dataset: Dataset) => {
      setFetching(true);
      setOpen(false);

      const extraParams = getSubmitExtras?.() ?? {};

      if (hasOnlyTraces) {
        addTracesToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            traceIds: validTraces.map((t) => t.id),
            enrichmentOptions: {
              include_spans: enrichmentOptions.includeSpans,
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
            ...extraParams,
          },
          {
            onSuccess: () => {
              onItemsAdded(true, false);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      } else if (hasOnlySpans) {
        addSpansToDataset(
          {
            workspaceName,
            datasetId: dataset.id,
            spanIds: validSpans.map((s) => s.id),
            enrichmentOptions: {
              include_tags: enrichmentOptions.includeTags,
              include_feedback_scores: enrichmentOptions.includeFeedbackScores,
              include_comments: enrichmentOptions.includeComments,
              include_usage: enrichmentOptions.includeUsage,
              include_metadata: enrichmentOptions.includeMetadata,
            },
            ...extraParams,
          },
          {
            onSuccess: () => {
              onItemsAdded(false, true);
              setFetching(false);
            },
            onError: () => {
              setFetching(false);
            },
          },
        );
      }
    },
    [
      setOpen,
      addTracesToDataset,
      addSpansToDataset,
      workspaceName,
      onItemsAdded,
      enrichmentOptions,
      getSubmitExtras,
      hasOnlyTraces,
      hasOnlySpans,
      validTraces,
      validSpans,
    ],
  );

  const handleDatasetSelect = useCallback(
    (datasetId: string) => {
      const dataset = datasetsById.get(datasetId) ?? null;
      if (!dataset || dataset.id === selectedDataset?.id) return;
      setSelectedDataset(dataset);
      onDatasetChange?.();
      setTimeout(() => {
        configSectionRef.current?.scrollIntoView({
          behavior: "smooth",
          block: "nearest",
        });
      }, 0);
    },
    [datasetsById, selectedDataset?.id, onDatasetChange],
  );

  return {
    selectedDataset,
    setSelectedDataset,
    handleDatasetSelect,
    datasetOptions,
    isPending,
    validTraces,
    validSpans,
    hasOnlyTraces,
    hasOnlySpans,
    noValidRows,
    partialValid,
    fetching,
    configSectionRef,
    enrichmentOptions,
    setEnrichmentOptions,
    submit,
  };
};

export default useAddToDatasetForm;
