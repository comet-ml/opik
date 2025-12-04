import React, { useMemo, useState } from "react";
import { Link } from "@tanstack/react-router";
import { ArrowRight, Split } from "lucide-react";
import isUndefined from "lodash/isUndefined";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import get from "lodash/get";

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
  extractOpenAIMessages,
  formatMessagesAsText,
  extractMessageContent,
  OpenAIMessage,
} from "@/lib/prompt";
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
  baselineExperiment?: Experiment | null;
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
  baselineExperiment,
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
                  percentage > 0 && "text-success",
                  percentage < 0 && "text-destructive",
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
