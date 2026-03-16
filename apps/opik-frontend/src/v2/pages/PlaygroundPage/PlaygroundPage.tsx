import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Loader } from "lucide-react";

import PlaygroundOutputs from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import PlaygroundAddVariant from "@/v2/pages/PlaygroundPage/PlaygroundAddVariant";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import PlaygroundPrompts from "@/v2/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompts";
import SetupProviderDialog from "@/v2/pages-shared/llm/SetupProviderDialog/SetupProviderDialog";
import {
  useTriggerProviderValidation,
  useIsRunning,
  usePromptCount,
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
    <div ref={ref} className="-mx-6 h-full">
      <div
        className="flex min-h-full w-fit min-w-full"
        style={
          {
            "--min-prompt-width": "720px",
            "--max-prompt-width": "1440px",
          } as React.CSSProperties
        }
      >
        <div
          className="flex min-w-0 flex-1 flex-col"
          style={{ maxWidth: `calc(${promptCount} * var(--max-prompt-width))` }}
        >
          <PlaygroundPrompts
            workspaceName={workspaceName}
            providerKeys={providerKeys}
            isPendingProviderKeys={isPendingProviderKeys}
          />

          <div className="flex flex-1">
            <PlaygroundOutputs
              datasetId={datasetId}
              versionName={versionName}
              versionHash={versionHash}
              onChangeDatasetId={setDatasetId}
              workspaceName={workspaceName}
            />
          </div>
        </div>

        <PlaygroundAddVariant providerKeys={providerKeys} containerRef={ref} />
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
