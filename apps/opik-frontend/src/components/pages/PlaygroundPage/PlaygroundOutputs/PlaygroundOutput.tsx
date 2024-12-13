import React, {
  forwardRef,
  useCallback,
  useId,
  useImperativeHandle,
  useMemo,
  useState,
} from "react";
import {
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
} from "@/types/playgroundPrompts";
import useOpenApiRunStreaming, {
  RunStreamingReturn,
} from "@/api/playground/useOpenApiRunStreaming";
import { getAlphabetLetter } from "@/lib/utils";
import useSpanCreateMutation from "@/api/traces/useSpanCreateMutation";
import useTraceCreateMutation from "@/api/traces/useTraceCreateMutation";
import { v7 } from "uuid";

// ALEX
// RENAME_FILES constants

import { SPAN_TYPE } from "@/types/traces";
import { transformMessageIntoProviderMessage } from "@/lib/playgroundPrompts";
import pick from "lodash/pick";
import PlaygroundOutputLoader from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputLoader/PlaygroundOutputLoader";

interface PlaygroundOutputProps {
  model: PLAYGROUND_MODEL_TYPE | "";
  messages: PlaygroundMessageType[];
  index: number;
  configs: PlaygroundPromptConfigsType;
}

export interface PlaygroundOutputRef {
  run: () => Promise<void>;
  stop: () => void;
}

const PLAYGROUND_TRACE_SPAN_NAME = "chat_completion_create";

const USAGE_FIELDS_TO_SEND = [
  "completion_tokens",
  "prompt_tokens",
  "total_tokens",
];

const PLAYGROUND_PROJECT_NAME = "playground";

// ALEX area-hidden

// ALEX log choices
const PlaygroundOutput = forwardRef<PlaygroundOutputRef, PlaygroundOutputProps>(
  ({ model, messages, index, configs }, ref) => {
    const id = useId();
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const [outputText, setOutputText] = useState<string | null>(null);

    const renderContent = () => {
      if (isLoading && !outputText) {
        return <PlaygroundOutputLoader />;
      }

      if (error) {
        return `Error: ${error}`;
      }

      return outputText;
    };

    const { mutateAsync: createSpanMutateAsync } = useSpanCreateMutation();
    const { mutateAsync: createTraceMutateAsync } = useTraceCreateMutation();

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

    // ALEX
    // IF THERE IS NO MODEL LOL
    // PUT IT INTO A SEPARATE FILE
    const createTraceSpan = useCallback(
      async ({
        startTime,
        endTime,
        result,
        usage,
        error,
        choices,
      }: RunStreamingReturn) => {
        const traceId = v7();
        const spanId = v7();

        // ALEX HANDLE ERRORS
        try {
          await createTraceMutateAsync({
            id: traceId,
            projectName: PLAYGROUND_PROJECT_NAME,
            name: PLAYGROUND_TRACE_SPAN_NAME,
            startTime,
            endTime,
            input: { messages: providerMessages },
            output: { output: result || error },
          });

          await createSpanMutateAsync({
            id: spanId,
            traceId,
            projectName: PLAYGROUND_PROJECT_NAME,
            type: SPAN_TYPE.llm,
            name: PLAYGROUND_TRACE_SPAN_NAME,
            startTime,
            endTime,
            input: { messages: providerMessages },
            output: { choices },
            usage: !usage ? undefined : pick(usage, USAGE_FIELDS_TO_SEND),
            metadata: {
              created_from: "openai",
              usage,
              model,
              parameters: configs,
            },
          });
        } catch {
          //   ALEX SHOW THERE WAS AN ERROR LOGGING
        }
      },
      [
        createTraceMutateAsync,
        createSpanMutateAsync,
        providerMessages,
        model,
        configs,
      ],
    );

    const exposedRun = useCallback(async () => {
      const streaming = await runStreaming();
      createTraceSpan(streaming);
    }, [runStreaming, createTraceSpan]);

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

    return (
      <div key={id} className="size-full min-w-[var(--min-prompt-width)]">
        {/*ALEX CHECK 100px*/}
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
