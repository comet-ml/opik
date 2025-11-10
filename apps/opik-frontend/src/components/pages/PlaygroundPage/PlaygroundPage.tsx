import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Loader } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import { PLAYGROUND_SELECTED_DATASET_KEY } from "@/constants/llm";
import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ResizablePromptContainer from "@/components/pages/PlaygroundPage/ResizablePromptContainer";
import SetupProviderDialog from "@/components/pages-shared/llm/SetupProviderDialog/SetupProviderDialog";
import { useTriggerProviderValidation } from "@/store/PlaygroundStore";

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [setupDialogOpen, setSetupDialogOpen] = useState(false);
  const [hasCheckedInitialProviders, setHasCheckedInitialProviders] =
    useState(false);
  const triggerProviderValidation = useTriggerProviderValidation();

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.provider) || [];
  }, [providerKeysData]);

  const [datasetId, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

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
        className="flex h-full w-fit min-w-full flex-col pt-6"
        style={
          {
            "--min-prompt-width": "560px",
            "--item-gap": "1.5rem",
          } as React.CSSProperties
        }
      >
        <ResizablePromptContainer
          workspaceName={workspaceName}
          providerKeys={providerKeys}
          isPendingProviderKeys={isPendingProviderKeys}
        />

        <div className="flex">
          <PlaygroundOutputs
            datasetId={datasetId}
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
    </>
  );
};

export default PlaygroundPage;
