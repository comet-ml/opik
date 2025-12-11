import React from "react";

import { Card, CardContent } from "@/components/ui/card";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { cn } from "@/lib/utils";
import {
  extractMessageContent,
  extractPromptData,
  OpenAIMessage,
  NamedPrompts,
} from "@/lib/prompt";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

export const ReadOnlyMessage: React.FC<{
  message: OpenAIMessage;
  index: number;
}> = ({ message, index }) => {
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
          <pre className="whitespace-pre-wrap font-sans text-sm">
            {content || ""}
          </pre>
        </div>
      </CardContent>
    </Card>
  );
};

export const MessagesList: React.FC<{ messages: OpenAIMessage[] }> = ({
  messages,
}) => (
  <div className="overflow-y-auto pb-2">
    {messages.map((message, index) => (
      <ReadOnlyMessage key={index} message={message} index={index} />
    ))}
  </div>
);

export const NamedPromptsList: React.FC<{ prompts: NamedPrompts }> = ({
  prompts,
}) => {
  const promptNames = Object.keys(prompts);

  return (
    <Accordion type="multiple" defaultValue={promptNames} className="w-full">
      {promptNames.map((name) => (
        <AccordionItem key={name} value={name} className="border-b-0">
          <AccordionTrigger className="h-auto px-0 py-2 hover:no-underline">
            <span className="comet-body-s-accented">{name}</span>
          </AccordionTrigger>
          <AccordionContent className="pb-2">
            <MessagesList messages={prompts[name]} />
          </AccordionContent>
        </AccordionItem>
      ))}
    </Accordion>
  );
};

type PromptDisplayProps = {
  data: unknown;
  fallback?: React.ReactNode;
};

export const PromptDisplay: React.FC<PromptDisplayProps> = ({
  data,
  fallback,
}) => {
  const extracted = extractPromptData(data);

  if (!extracted) {
    return fallback ? <>{fallback}</> : null;
  }

  if (extracted.type === "single") {
    return <MessagesList messages={extracted.data} />;
  }

  return <NamedPromptsList prompts={extracted.data} />;
};
