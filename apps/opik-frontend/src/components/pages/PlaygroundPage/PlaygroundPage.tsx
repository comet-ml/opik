import React, { useEffect, useMemo } from "react";
import { Loader } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";

import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";
import useAppStore from "@/store/AppStore";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import PlaygroundPrompts from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompts";
import ResizableBottomDiv from "@/components/shared/ResizableBottomDiv/ResizableBottomDiv";

const PLAYGROUND_SELECTED_DATASET_KEY = "playground-selected-dataset";
const LEGACY_PLAYGROUND_PROMPTS_KEY = "playground-prompts-state";
const PLAYGROUND_PROMPT_HEIGHT_KEY = "playground-prompts-height";
const PLAYGROUND_PROMPT_MIN_HEIGHT = 190;

const PlaygroundPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const defaultPromptHeight = window.innerHeight - 300;
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
          "--min-prompt-width": "540px",
          "--item-gap": "1.5rem",
        } as React.CSSProperties
      }
    >
      <ResizableBottomDiv
        minHeight={PLAYGROUND_PROMPT_MIN_HEIGHT}
        localStorageKey={PLAYGROUND_PROMPT_HEIGHT_KEY}
        defaultHeight={defaultPromptHeight}
      >
        <div className="size-full">
          <PlaygroundPrompts
            workspaceName={workspaceName}
            providerKeys={providerKeys}
            isPendingProviderKeys={isPendingProviderKeys}
          />
        </div>
      </ResizableBottomDiv>

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
