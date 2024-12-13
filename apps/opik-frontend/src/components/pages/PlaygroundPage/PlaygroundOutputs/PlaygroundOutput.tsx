import React, {
  forwardRef,
  useCallback,
  useId,
  useImperativeHandle,
  useMemo,
  useState,
} from "react";
import {
  PLAYGROUND_MODEL,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
} from "@/types/playground";
import useOpenApiRunStreaming from "@/api/playground/useOpenApiRunStreaming";
import { getAlphabetLetter } from "@/lib/utils";
import { transformMessageIntoProviderMessage } from "@/lib/playground";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import useCreateOutputTraceAndSpan from "@/api/playground/useCreateOutputTraceAndSpan";

interface PlaygroundOutputProps {
  model: PLAYGROUND_MODEL | "";
  messages: PlaygroundMessageType[];
  index: number;
  configs: PlaygroundPromptConfigsType;
}

export interface PlaygroundOutputRef {
  run: () => Promise<void>;
  stop: () => void;
}

const PlaygroundOutput = forwardRef<PlaygroundOutputRef, PlaygroundOutputProps>(
  ({ model, messages, index, configs }, ref) => {
    const id = useId();
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [outputText, setOutputText] = useState<string | null>(null);

    const providerMessages = useMemo(() => {
      return messages.map(transformMessageIntoProviderMessage);
    }, [messages]);

    // @ToDo: when we add providers, add a function to pick the provider
    const { runStreaming, stop } = useOpenApiRunStreaming({
      model,
      configs,
      messages: providerMessages,
      onAddChunk: setOutputText,
      onLoading: setIsLoading,
      onError: setError,
    });

    const createOutputTraceAndSpan = useCreateOutputTraceAndSpan();

    const exposedRun = useCallback(async () => {
      const streaming = await runStreaming();
      createOutputTraceAndSpan({
        ...streaming,
        model,
        configs,
        providerMessages,
      });
    }, [
      runStreaming,
      createOutputTraceAndSpan,
      model,
      configs,
      providerMessages,
    ]);

    const exposedStop = useCallback(() => {
      stop();
    }, [stop]);

    useImperativeHandle(
      ref,
      () => ({
        run: exposedRun,
        stop: exposedStop,
      }),
      [exposedRun, exposedStop],
    );

    const renderContent = () => {
      if (isLoading && !outputText) {
        return <PlaygroundOutputLoader />;
      }

      if (error) {
        return `Error: ${error}`;
      }

      return outputText;
    };

    return (
      <div key={id} className="size-full min-w-[var(--min-prompt-width)]">
        <p className="comet-body-s-accented my-3">
          Output {getAlphabetLetter(index)}
        </p>
        <div className="comet-body-s min-h-[100px] rounded-lg border bg-white p-3">
          {renderContent()}
        </div>
      </div>
    );
  },
);

PlaygroundOutput.displayName = "PlaygroundOutput";

export default PlaygroundOutput;
