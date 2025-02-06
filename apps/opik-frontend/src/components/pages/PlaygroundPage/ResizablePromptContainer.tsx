import React, { useCallback, useEffect, useState } from "react";
import { Resizable, ResizeCallback } from "re-resizable";
import useLocalStorageState from "use-local-storage-state";

const PLAYGROUND_PROMPT_HEIGHT_KEY = "playground-prompts-height";
const PLAYGROUND_PROMPT_MIN_HEIGHT = 190;

interface ResizableDivContainerProps {
  children: React.ReactNode;
}

const ResizablePromptContainer = ({ children }: ResizableDivContainerProps) => {
  const defaultHeight = Math.max(
    window.innerHeight - 300,
    PLAYGROUND_PROMPT_MIN_HEIGHT,
  );

  const [height, setHeight] = useLocalStorageState(
    PLAYGROUND_PROMPT_HEIGHT_KEY,
    { defaultValue: defaultHeight },
  );

  const [maxHeight, setMaxHeight] = useState(window.innerHeight - 100);

  const onResizeStop: ResizeCallback = useCallback(
    (e, direction, ref, delta) => {
      setHeight((h) => h + delta.height);
    },
    [setHeight],
  );

  useEffect(() => {
    const updateMaxHeight = () => setMaxHeight(window.innerHeight - 100);
    window.addEventListener("resize", updateMaxHeight);

    return () => window.removeEventListener("resize", updateMaxHeight);
  }, []);

  return (
    <Resizable
      enable={{ bottom: true }}
      defaultSize={{ height: height }}
      className="border-b"
      minHeight={PLAYGROUND_PROMPT_MIN_HEIGHT}
      maxHeight={maxHeight}
      onResizeStop={onResizeStop}
    >
      {children}
    </Resizable>
  );
};

export default ResizablePromptContainer;
