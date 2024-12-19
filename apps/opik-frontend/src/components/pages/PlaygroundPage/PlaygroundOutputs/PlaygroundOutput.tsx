import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useState,
} from "react";
import {
  PlaygroundOutputType,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
} from "@/types/playground";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import { getAlphabetLetter } from "@/lib/utils";
import { transformMessageIntoProviderMessage } from "@/lib/playground";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";
import useCreateOutputTraceAndSpan from "@/api/playground/useCreateOutputTraceAndSpan";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

interface PlaygroundOutputProps {
  model: PROVIDER_MODEL_TYPE | "";
  workspaceName: string;
  messages: PlaygroundMessageType[];
  index: number;
  configs: PlaygroundPromptConfigsType;
  output: PlaygroundOutputType;
  onOutputChange: (output: PlaygroundOutputType) => void;
}

export interface PlaygroundOutputRef {
  run: () => Promise<void>;
  stop: () => void;
}

const PlaygroundOutput = forwardRef<PlaygroundOutputRef, PlaygroundOutputProps>(
  (
    { model, messages, index, configs, onOutputChange, output, workspaceName },
    ref,
  ) => {
    const [isLoading, setIsLoading] = useState(false);

    const providerMessages = useMemo(() => {
      return messages.map(transformMessageIntoProviderMessage);
    }, [messages]);

    // @ToDo: when we add providers, add a function to pick the provider
    const { runStreaming, stop } = useCompletionProxyStreaming({
      model,
      configs,
      messages: providerMessages,
      onAddChunk: onOutputChange,
      workspaceName,
    });

    const createOutputTraceAndSpan = useCreateOutputTraceAndSpan();

    const exposedRun = useCallback(async () => {
      setIsLoading(true);
      onOutputChange(null);

      const streaming = await runStreaming();

      setIsLoading(false);

      const error = streaming.providerError || streaming.opikError;

      if (error) {
        onOutputChange(error);
      }

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
      onOutputChange,
    ]);

    useImperativeHandle(
      ref,
      () => ({
        run: exposedRun,
        stop: stop,
      }),
      [exposedRun, stop],
    );

    const renderContent = () => {
      if (isLoading && !output) {
        return <PlaygroundOutputLoader />;
      }

      return output;
    };

    return (
      <div className="size-full min-w-[var(--min-prompt-width)]">
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
