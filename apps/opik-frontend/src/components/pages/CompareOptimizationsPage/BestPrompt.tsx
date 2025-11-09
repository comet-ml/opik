import React, { useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowRight, Split } from "lucide-react";
import isUndefined from "lodash/isUndefined";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isString from "lodash/isString";
import get from "lodash/get";
import first from "lodash/first";

import { OPTIMIZATION_PROMPT_KEY } from "@/constants/experiments";
import useAppStore from "@/store/AppStore";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { formatNumericData, toString } from "@/lib/utils";
import ColoredTagNew from "@/components/shared/ColoredTag/ColoredTagNew";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { cn } from "@/lib/utils";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";

type BestPromptProps = {
  optimization: Optimization;
  experiment: Experiment;
  scoreMap: Record<string, { score: number; percentage?: number }>;
  experiments: Experiment[];
};

type OpenAIMessage = {
  role: string;
  content:
    | string
    | Array<{ type: string; text?: string; [key: string]: unknown }>;
};

/**
 * Extracts text content from OpenAI message format.
 * Handles both string content and array content (extracts text from {type: "text", text: "..."} items).
 */
const extractMessageContent = (
  content:
    | string
    | Array<{ type: string; text?: string; [key: string]: unknown }>
    | unknown,
): string => {
  if (isString(content)) {
    return content;
  }

  if (isArray(content)) {
    const textParts: string[] = [];
    for (const item of content) {
      if (
        isObject(item) &&
        "type" in item &&
        item.type === "text" &&
        "text" in item &&
        isString(item.text)
      ) {
        textParts.push(item.text);
      }
    }
    return textParts.join("\n");
  }

  return "";
};

/**
 * Validates if an array contains valid OpenAI message objects.
 */
const isValidOpenAIMessages = (messages: unknown[]): boolean => {
  return messages.every(
    (msg: unknown) =>
      isObject(msg) &&
      "role" in msg &&
      isString(msg.role) &&
      ("content" in msg || "text" in msg),
  );
};

/**
 * Extracts OpenAI messages from various data formats.
 * Handles both array format and object with messages property.
 */
const extractOpenAIMessages = (data: unknown): OpenAIMessage[] | null => {
  // Check if it's an array of messages (OpenAI format)
  if (isArray(data)) {
    if (isValidOpenAIMessages(data)) {
      return data as OpenAIMessage[];
    }
  }

  // Check if it's an object with a messages array
  if (isObject(data) && "messages" in data) {
    const promptObj = data as { messages?: unknown };
    if (isArray(promptObj.messages)) {
      if (isValidOpenAIMessages(promptObj.messages)) {
        return promptObj.messages as OpenAIMessage[];
      }
    }
  }

  return null;
};

/**
 * Formats OpenAI messages as readable text.
 */
const formatMessagesAsText = (messages: OpenAIMessage[]): string => {
  return messages
    .map((msg) => {
      const roleName =
        LLM_MESSAGE_ROLE_NAME_MAP[
          msg.role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP
        ] || msg.role;
      const content = extractMessageContent(msg.content);
      return `${roleName}: ${content}`;
    })
    .join("\n\n");
};

/**
 * Read-only message component similar to LLMPromptMessage but simplified.
 */
const ReadOnlyMessage: React.FC<{ message: OpenAIMessage; index: number }> = ({
  message,
  index,
}) => {
  const roleName =
    LLM_MESSAGE_ROLE_NAME_MAP[
      message.role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP
    ] || message.role;
  const content = extractMessageContent(message.content);

  return (
    <Card className={cn("py-2 px-3", index > 0 && "mt-2")}>
      <CardContent className="p-0">
        <div className="mb-2 flex items-center gap-2">
          <span className="comet-body-s-accented">{roleName}</span>
        </div>
        <div className="mt-1">
          <MarkdownPreview>{content || ""}</MarkdownPreview>
        </div>
      </CardContent>
    </Card>
  );
};

const BestPrompt: React.FC<BestPromptProps> = ({
  optimization,
  experiment,
  scoreMap,
  experiments,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [diffOpen, setDiffOpen] = useState(false);

  const { score, percentage } = useMemo(() => {
    const retVal: {
      score?: number;
      percentage?: number;
    } = {
      score: undefined,
      percentage: undefined,
    };

    const scoreObject = scoreMap[experiment.id];
    if (!scoreObject) return retVal;

    retVal.score = scoreObject.score;
    retVal.percentage = scoreObject.percentage;

    return retVal;
  }, [experiment.id, scoreMap]);

  const promptData = useMemo(() => {
    return get(experiment.metadata ?? {}, OPTIMIZATION_PROMPT_KEY, "-");
  }, [experiment]);

  const messages = useMemo((): OpenAIMessage[] | null => {
    if (!isObject(promptData) && !isArray(promptData)) {
      return null;
    }

    return extractOpenAIMessages(promptData);
  }, [promptData]);

  const fallbackPrompt = useMemo(() => {
    if (messages) {
      return null;
    }
    return isObject(promptData)
      ? JSON.stringify(promptData, null, 2)
      : toString(promptData);
  }, [promptData, messages]);

  const baselineExperiment = useMemo(() => {
    if (!experiments || experiments.length === 0) return null;
    const sorted = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return first(sorted);
  }, [experiments]);

  const baselinePrompt = useMemo(() => {
    if (!baselineExperiment) return null;
    const val = get(
      baselineExperiment.metadata ?? {},
      OPTIMIZATION_PROMPT_KEY,
      null,
    );
    if (!val) return null;

    const extractedMessages = extractOpenAIMessages(val);
    if (extractedMessages) {
      return formatMessagesAsText(extractedMessages);
    }

    return isObject(val) ? JSON.stringify(val, null, 2) : toString(val);
  }, [baselineExperiment]);

  const currentPromptText = useMemo(() => {
    if (messages) {
      return formatMessagesAsText(messages);
    }
    return fallbackPrompt || "";
  }, [messages, fallbackPrompt]);

  return (
    <Card className="size-full">
      <CardHeader className="gap-y-0.5 px-5">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
            <CardDescription className="!mt-0">
              <ColoredTagNew
                label={optimization.objective_name}
                size="sm"
                className="px-0"
              />
            </CardDescription>
          </div>
          <div className="flex flex-row items-baseline gap-2">
            <div className="comet-title-xl text-4xl leading-none text-foreground-secondary">
              {isUndefined(score) ? "-" : formatNumericData(score)}
            </div>
            {!isUndefined(percentage) && (
              <div
                className={cn(
                  "comet-body-s-accented",
                  percentage > 0 && "text-green-600 dark:text-green-500",
                  percentage < 0 && "text-red-600 dark:text-red-500",
                  percentage === 0 && "text-muted-slate",
                )}
              >
                {percentage > 0 ? "+" : ""}
                {formatNumericData(percentage)}%
              </div>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="px-5 pb-4">
        <div className="flex items-center justify-between">
          <Link
            to="/$workspaceName/optimizations/$datasetId/$optimizationId/compare"
            params={{
              workspaceName,
              datasetId: experiment.dataset_id,
              optimizationId: optimization.id,
            }}
            search={{ trials: [experiment.id] }}
          >
            <Button variant="ghost" className="flex items-center pl-0">
              View details <ArrowRight className="size-4" />
            </Button>
          </Link>
          {baselinePrompt && (
            <>
              <TooltipWrapper content="Compare with baseline prompt">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setDiffOpen(true)}
                  className="flex items-center gap-1"
                >
                  <Split className="size-4" />
                  Diff
                </Button>
              </TooltipWrapper>
              <Dialog open={diffOpen} onOpenChange={setDiffOpen}>
                <DialogContent className="max-w-lg sm:max-w-[880px]">
                  <DialogHeader>
                    <DialogTitle>Compare prompts</DialogTitle>
                  </DialogHeader>
                  <div className="grid grid-cols-2 gap-4 pb-2">
                    <div>
                      <div className="mb-2 px-0.5">
                        <span className="comet-body-s-accented">Baseline</span>
                      </div>
                      <div className="comet-code h-[620px] overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                        {baselinePrompt}
                      </div>
                    </div>
                    <div>
                      <div className="mb-2 px-0.5">
                        <span className="comet-body-s-accented">Current</span>
                      </div>
                      <div className="comet-code h-[620px] overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                        <TextDiff
                          content1={baselinePrompt}
                          content2={currentPromptText}
                        />
                      </div>
                    </div>
                  </div>
                </DialogContent>
              </Dialog>
            </>
          )}
        </div>
        <div>
          {messages ? (
            <div className="overflow-y-auto pb-2">
              {messages.map((message, index) => (
                <ReadOnlyMessage key={index} message={message} index={index} />
              ))}
            </div>
          ) : (
            <TooltipWrapper content={fallbackPrompt || ""}>
              <div className="comet-body-s line-clamp-2 h-11 text-light-slate">
                {fallbackPrompt}
              </div>
            </TooltipWrapper>
          )}
        </div>
      </CardContent>
    </Card>
  );
};

export default BestPrompt;
