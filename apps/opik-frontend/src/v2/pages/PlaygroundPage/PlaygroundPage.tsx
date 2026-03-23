import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Loader } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";

import { Separator } from "@/ui/separator";
import PlaygroundOutputs from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import PlaygroundAddVariant from "@/v2/pages/PlaygroundPage/PlaygroundAddVariant";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import PlaygroundPrompts from "@/v2/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompts";
import PlaygroundHeader from "@/v2/pages/PlaygroundPage/PlaygroundHeader";
import SetupProviderDialog from "@/v2/pages-shared/llm/SetupProviderDialog/SetupProviderDialog";
import useActionButtonActions from "@/v2/pages/PlaygroundPage/useActionButtonActions";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import {
  useTriggerProviderValidation,
  useIsRunning,
  usePromptCount,
  useDatasetFilters,
  useDatasetPage,
  useDatasetSize,
} from "@/store/PlaygroundStore";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { Dataset, DatasetItem } from "@/types/datasets";
import { transformDataColumnFilters } from "@/lib/filters";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";
import { usePermissions } from "@/contexts/PermissionsContext";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";

import { DEFAULT_LOADED_DATASETS } from "@/v2/pages-shared/DatasetVersionSelectBox/useDatasetVersionSelect";

const EMPTY_ITEMS: DatasetItem[] = [];
const EMPTY_DATASETS: Dataset[] = [];

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canViewDatasets },
  } = usePermissions();
  const [setupDialogOpen, setSetupDialogOpen] = useState(false);
  const [hasCheckedInitialProviders, setHasCheckedInitialProviders] =
    useState(false);
  const triggerProviderValidation = useTriggerProviderValidation();
  const isRunning = useIsRunning();
  const promptCount = usePromptCount();
  const ref = useRef<HTMLDivElement>(null);

  const { datasetId, versionName, versionHash, setDatasetId } =
    usePlaygroundDataset();

  const { DialogComponent } = useNavigationBlocker({
    condition: isRunning,
    title: datasetId
      ? "Experiment execution in progress"
      : "Prompt execution in progress",
    description: datasetId
      ? "Your experiment is currently running. Leaving now will interrupt the execution and may result in incomplete experiment items. Are you sure you want to leave?"
      : "Your prompt is currently running. Leaving now will interrupt the execution and may result in incomplete traces. Are you sure you want to leave?",
    confirmText: "Leave anyway",
    cancelText: "Stay and wait",
  });

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({ workspaceName });

  const providerKeys: COMPOSED_PROVIDER_TYPE[] = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.ui_composed_provider) || [];
  }, [providerKeysData]);

  // Auto-open setup dialog when no providers configured (only on initial load)
  useEffect(() => {
    if (!isPendingProviderKeys && !hasCheckedInitialProviders) {
      setHasCheckedInitialProviders(true);
      if (providerKeys.length === 0) {
        setSetupDialogOpen(true);
      }
    }
  }, [isPendingProviderKeys, hasCheckedInitialProviders, providerKeys.length]);

  // Handle provider addition - trigger validation for all prompts
  const handleProviderAdded = useCallback(() => {
    triggerProviderValidation();
  }, [triggerProviderValidation]);

  const parsed = useMemo(() => parseDatasetVersionKey(datasetId), [datasetId]);
  const plainDatasetId = parsed?.datasetId || datasetId;
  const parsedVersionId = parsed?.versionId;

  const filters = useDatasetFilters();
  const page = useDatasetPage();
  const size = useDatasetSize();

  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const { data: datasetItemsData, isFetching: isFetchingDatasetItems } =
    useDatasetItemsList(
      {
        datasetId: plainDatasetId!,
        page,
        size,
        truncate: true,
        filters: transformedFilters,
        versionId: versionHash,
      },
      {
        enabled: !!plainDatasetId,
        placeholderData: plainDatasetId ? keepPreviousData : undefined,
      },
    );
  const datasetItems = datasetItemsData?.content || EMPTY_ITEMS;

  const { data: datasetsData } = useDatasetsList(
    { workspaceName, page: 1, size: DEFAULT_LOADED_DATASETS },
    { enabled: canViewDatasets && !!plainDatasetId },
  );
  const datasetName =
    (datasetsData?.content || EMPTY_DATASETS).find(
      (ds) => ds.id === plainDatasetId,
    )?.name || null;

  const { runAll, stopAll, runSingle, stopSingle } = useActionButtonActions({
    workspaceName,
    datasetItems,
    datasetName,
    datasetVersionId: parsedVersionId || undefined,
  });

  const [pendingRun, setPendingRun] = useState(false);

  const isExperimentMode = !!datasetId;

  useEffect(() => {
    if (pendingRun && plainDatasetId && !isFetchingDatasetItems) {
      setPendingRun(false);
      if (datasetItems.length > 0) {
        runAll();
      }
    }
  }, [
    pendingRun,
    datasetItems.length,
    plainDatasetId,
    isFetchingDatasetItems,
    runAll,
  ]);

  // Used by the "Run on dataset" dialog: the dataset ID may have just changed
  // so we defer runAll until items are loaded and the effect above fires.
  const handleDeferredRunAll = useCallback(() => {
    setPendingRun(true);
  }, []);

  // Keyboard shortcut: Shift+Enter to run all
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.shiftKey && event.key === "Enter" && !isRunning) {
        event.preventDefault();
        event.stopPropagation();
        runAll();
      }
    };
    window.addEventListener("keydown", handleKeyDown, true);
    return () => window.removeEventListener("keydown", handleKeyDown, true);
  }, [runAll, isRunning]);

  useEffect(() => {
    return () => stopAll();
  }, [stopAll]);

  if (isPendingProviderKeys) {
    return <Loader />;
  }

  const headerMaxWidth = `calc(${promptCount} * var(--max-prompt-width) + var(--add-variant-width))`;

  return (
    <div
      ref={ref}
      className="-mx-6 h-full overflow-y-auto overflow-x-hidden"
      style={
        {
          "--min-prompt-width": "400px",
          "--max-prompt-width": "1440px",
          "--add-variant-width": "90px",
        } as React.CSSProperties
      }
    >
      <div className="flex min-h-full min-w-full flex-col bg-background">
        <div className="bg-gray-100">
          <PlaygroundHeader
            workspaceName={workspaceName}
            providerKeys={providerKeys}
            datasetId={datasetId}
            datasetName={datasetName}
            versionName={versionName}
            onChangeDatasetId={setDatasetId}
            onRunAll={runAll}
            onDeferredRunAll={handleDeferredRunAll}
            onStopAll={stopAll}
            maxWidth={headerMaxWidth}
          />
        </div>

        <Separator />

        {isExperimentMode ? (
          <>
            <div className="flex h-[50vh] shrink-0 overflow-x-auto">
              <div
                className="flex flex-1 shrink-0"
                style={{
                  minWidth: `calc(${promptCount} * var(--min-prompt-width))`,
                  maxWidth: `calc(${promptCount} * var(--max-prompt-width))`,
                }}
              >
                <PlaygroundPrompts
                  workspaceName={workspaceName}
                  providerKeys={providerKeys}
                  isPendingProviderKeys={isPendingProviderKeys}
                />
              </div>

              <PlaygroundAddVariant providerKeys={providerKeys} />
            </div>

            <PlaygroundOutputs
              datasetId={datasetId}
              versionHash={versionHash}
              runSingle={runSingle}
              stopSingle={stopSingle}
            />
          </>
        ) : (
          <div className="flex min-h-0 flex-1 overflow-x-auto">
            <div
              className="flex flex-1 shrink-0 flex-col"
              style={{
                minWidth: `calc(${promptCount} * var(--min-prompt-width))`,
                maxWidth: `calc(${promptCount} * var(--max-prompt-width))`,
              }}
            >
              <div className="flex h-[50vh] shrink-0">
                <PlaygroundPrompts
                  workspaceName={workspaceName}
                  providerKeys={providerKeys}
                  isPendingProviderKeys={isPendingProviderKeys}
                />
              </div>

              <div className="flex flex-1">
                <PlaygroundOutputs
                  datasetId={datasetId}
                  versionHash={versionHash}
                  runSingle={runSingle}
                  stopSingle={stopSingle}
                />
              </div>
            </div>

            <PlaygroundAddVariant providerKeys={providerKeys} />
          </div>
        )}
      </div>

      <SetupProviderDialog
        open={setupDialogOpen}
        setOpen={setSetupDialogOpen}
        onProviderAdded={handleProviderAdded}
      />

      {DialogComponent}
    </div>
  );
};

export default PlaygroundPage;
