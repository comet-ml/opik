import React, { useEffect, useId, useRef, useState } from "react";
import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import useOpenApiRunStreaming from "@/api/playground/useOpenApiRunStreaming";
import { getAlphabetLetter } from "@/lib/utils";

interface PlaygroundOutputProps {
  runId: number;
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: PlaygroundMessageType[];
  index: number;
}

// ALEX area-hidden

const PlaygroundOutput = ({
  model,
  messages,
  runId,
  index,
}: PlaygroundOutputProps) => {
  const id = useId();
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const lastGlobalRunId = useRef(runId);

  const [outputText, setOutputText] = useState<string | null>(null);

  const renderContent = () => {
    if (isLoading && !outputText) {
      return "Loading...";
    }

    if (error) {
      return `Error: ${error}`;
    }

    return outputText;
  };

  const runStreaming = useOpenApiRunStreaming({
    model,
    messages,
    onAddChunk: setOutputText,
    onLoading: setIsLoading,
    onError: setError,
  });

  useEffect(() => {
    if (runId && lastGlobalRunId.current !== runId) {
      runStreaming();
      lastGlobalRunId.current = runId;
    }
  }, [runId, runStreaming]);

  return (
    <div key={id} className="size-full min-w-[var(--min-prompt-width)]">
      {/*ALEX CHECK 100px*/}
      <p className="comet-body-s-accented my-3">
        Output {getAlphabetLetter(index)}
      </p>
      {/*break-words whitespace-normal*/}
      <p className="comet-body-s min-h-[100px] rounded-lg border bg-white p-3">
        {renderContent()}
      </p>
    </div>
  );
};

export default PlaygroundOutput;
