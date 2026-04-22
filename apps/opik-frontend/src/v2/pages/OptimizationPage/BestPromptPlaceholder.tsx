import React from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/ui/card";
import { OptimizationStudioConfig } from "@/types/optimizations";
import ColoredTagNew from "@/shared/ColoredTag/ColoredTagNew";
import { MessagesList } from "@/v2/pages-shared/prompts/PromptMessageDisplay";
import { extractDisplayMessages } from "@/lib/llm";

interface BestPromptPlaceholderProps {
  objectiveName: string;
  studioConfig: OptimizationStudioConfig;
}

const BestPromptPlaceholder: React.FC<BestPromptPlaceholderProps> = ({
  objectiveName,
  studioConfig,
}) => {
  const messages = extractDisplayMessages(studioConfig.prompt?.messages);

  return (
    <Card className="size-full">
      <CardHeader className="gap-y-0.5 px-5">
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
            <CardDescription className="!mt-0">
              <ColoredTagNew label={objectiveName} size="sm" className="px-0" />
            </CardDescription>
          </div>
          <div className="flex flex-row items-baseline gap-2">
            <div className="comet-title-xl text-4xl leading-none text-foreground-secondary">
              -
            </div>
          </div>
        </div>
      </CardHeader>
      <CardContent className="px-5 pb-4">
        <div className="comet-body-s mb-3 text-muted-slate">
          Waiting for trials...
        </div>
        <div className="comet-body-xs mb-2 text-muted-slate">Prompt</div>
        {messages && messages.length > 0 ? (
          <MessagesList messages={messages} />
        ) : (
          <div className="comet-body-s text-muted-slate">
            No prompt messages
          </div>
        )}
      </CardContent>
    </Card>
  );
};

export default BestPromptPlaceholder;
