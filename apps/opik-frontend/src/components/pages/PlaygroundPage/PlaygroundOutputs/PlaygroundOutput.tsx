import React, { useEffect, useId, useRef } from "react";
import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
} from "@/types/playgroundPrompts";
import usePlaygroundPromptRun from "@/api/playground/usePlaygroundPromptRun";
import { keepPreviousData } from "@tanstack/react-query";
import { AxiosError } from "axios";

interface PlaygroundOutputProps {
  promptId: string;
  runId: number;
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: PlaygroundMessageType[];
}

// ALEX ADD ERROR

// ALEX area-hidden

// ALEX HANDLE ERRORS BETTER
type OpenAIError = {
  error: {
    message: string;
  };
};

const PlaygroundOutput = ({
  promptId,
  model,
  messages,
  runId,
}: PlaygroundOutputProps) => {
  const id = useId();
  const isInitializedRef = useRef(false);

  const {
    data: run,
    isLoading: isRunLoading,
    isError,
    error,
    refetch,
  } = usePlaygroundPromptRun(
    {
      messages,
      model: model as PLAYGROUND_MODEL_TYPE,
      promptId,
    },
    {
      placeholderData: keepPreviousData,
      enabled: false,
    },
  );

  const renderContent = () => {
    if (isRunLoading) {
      return "Loading...";
    }

    if (isError) {
      const localError = error as AxiosError<OpenAIError>;

      return `Error: ${localError?.response?.data?.error?.message}`;
    }

    return run?.choices?.[0]?.message?.content;
  };

  useEffect(() => {
    if (runId && isInitializedRef.current) {
      refetch();
    }

    isInitializedRef.current = true;
  }, [runId, refetch]);

  return (
    <div key={id} className="w-full min-w-[var(--min-prompt-width)]">
      {/*ALEX CHECK 100px*/}
      <p className="comet-body-s min-h-[100px] break-all rounded border bg-white p-3">
        {renderContent()}
      </p>
    </div>
  );
};

export default PlaygroundOutput;
