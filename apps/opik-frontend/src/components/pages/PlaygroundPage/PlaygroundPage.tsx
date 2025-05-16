import React, { useEffect, useMemo } from "react";
import { Loader } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import ResizablePromptContainer from "@/components/pages/PlaygroundPage/ResizablePromptContainer";

const PLAYGROUND_SELECTED_DATASET_KEY = "playground-selected-dataset";
const LEGACY_PLAYGROUND_PROMPTS_KEY = "playground-prompts-state";

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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

  // @todo: remove later
  // this field is not used anymore
  useEffect(() => {
    localStorage.removeItem(LEGACY_PLAYGROUND_PROMPTS_KEY);
  }, []);

  if (isPendingProviderKeys) {
    return <Loader />;
  }

  return (
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
  );
};

export default PlaygroundPage;
