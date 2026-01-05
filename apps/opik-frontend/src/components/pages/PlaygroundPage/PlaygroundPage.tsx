import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Loader } from "lucide-react";

import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ResizablePromptContainer from "@/components/pages/PlaygroundPage/ResizablePromptContainer";
import SetupProviderDialog from "@/components/pages-shared/llm/SetupProviderDialog/SetupProviderDialog";
import {
  useTriggerProviderValidation,
  useIsRunning,
} from "@/store/PlaygroundStore";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [setupDialogOpen, setSetupDialogOpen] = useState(false);
  const [hasCheckedInitialProviders, setHasCheckedInitialProviders] =
    useState(false);
  const triggerProviderValidation = useTriggerProviderValidation();
  const isRunning = useIsRunning();

  const { datasetId, versionName, versionHash, setDatasetId } =
    usePlaygroundDataset();
  const hasDataset = !!datasetId;

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
    useProviderKeys({
      workspaceName,
    });

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

  if (isPendingProviderKeys) {
    return <Loader />;
  }

  return (
    <>
      <div
        className={`flex h-full w-fit min-w-full flex-col pt-6 ${
          hasDataset ? "h-auto w-full" : ""
        }`}
        style={
          {
            "--min-prompt-width": "700px",
            "--item-gap": "1.5rem",
          } as React.CSSProperties
        }
      >
        <ResizablePromptContainer
          workspaceName={workspaceName}
          providerKeys={providerKeys}
          isPendingProviderKeys={isPendingProviderKeys}
          hasDataset={hasDataset}
        />

        <div className="flex">
          <PlaygroundOutputs
            datasetId={datasetId}
            versionName={versionName}
            versionHash={versionHash}
            onChangeDatasetId={setDatasetId}
            workspaceName={workspaceName}
          />
        </div>
      </div>

      <SetupProviderDialog
        open={setupDialogOpen}
        setOpen={setSetupDialogOpen}
        onProviderAdded={handleProviderAdded}
      />

      {DialogComponent}
    </>
  );
};

export default PlaygroundPage;
