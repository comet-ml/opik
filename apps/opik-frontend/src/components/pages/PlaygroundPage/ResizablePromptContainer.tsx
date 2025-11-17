import React, { useCallback, useEffect, useState } from "react";
import { Resizable, ResizeCallback } from "re-resizable";
import useLocalStorageState from "use-local-storage-state";
import PlaygroundPrompts from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompts";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";

const PLAYGROUND_PROMPT_MIN_HEIGHT = 190;
export const PLAYGROUND_PROMPT_HEIGHT_KEY = "playground-prompts-height";

const calculateMaxHeight = () => window.innerHeight - 100;
const calculateDefaultHeight = () =>
  Math.max(window.innerHeight - 360, PLAYGROUND_PROMPT_MIN_HEIGHT);

interface ResizableDivContainerProps {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  hasDataset: boolean;
}

const ResizablePromptContainer = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
  hasDataset,
}: ResizableDivContainerProps) => {
  const defaultHeight = calculateDefaultHeight();

  const [height, setHeight] = useLocalStorageState(
    PLAYGROUND_PROMPT_HEIGHT_KEY,
    { defaultValue: defaultHeight },
  );

  const [maxHeight, setMaxHeight] = useState(calculateMaxHeight());

  const handleResetHeight = useCallback(() => {
    setHeight(defaultHeight);
  }, [defaultHeight, setHeight]);

  const onResizeStop: ResizeCallback = useCallback(
    (e, direction, ref, delta) => {
      setHeight((h) => h + delta.height);
    },
    [setHeight],
  );

  useEffect(() => {
    const updateMaxHeight = () => setMaxHeight(calculateMaxHeight());
    window.addEventListener("resize", updateMaxHeight);

    return () => window.removeEventListener("resize", updateMaxHeight);
  }, []);

  return (
    <Resizable
      enable={{ bottom: true }}
      size={{ height }}
      className="border-b pb-1"
      minHeight={PLAYGROUND_PROMPT_MIN_HEIGHT}
      maxHeight={maxHeight}
      onResizeStop={onResizeStop}
    >
      <div className="size-full">
        <PlaygroundPrompts
          workspaceName={workspaceName}
          providerKeys={providerKeys}
          isPendingProviderKeys={isPendingProviderKeys}
          onResetHeight={handleResetHeight}
          hasDataset={hasDataset}
        />
      </div>
    </Resizable>
  );
};

export default ResizablePromptContainer;
