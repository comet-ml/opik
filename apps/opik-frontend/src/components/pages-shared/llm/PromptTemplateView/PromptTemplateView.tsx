import React, { useMemo, useState } from "react";
import { Code2, MessageSquare, Type } from "lucide-react";
import { Button } from "@/components/ui/button";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import PromptMessagesReadonly, {
  ChatMessage,
} from "@/components/pages-shared/llm/PromptMessagesReadonly/PromptMessagesReadonly";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

export const parseMessagesFromTemplate = (template: unknown): ChatMessage[] => {
  try {
    const data = typeof template === "string" ? JSON.parse(template) : template;
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
};

interface PromptTemplateViewProps {
  template: unknown;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  search?: string;
  children?: React.ReactNode;
}

const PromptTemplateView: React.FC<PromptTemplateViewProps> = ({
  template,
  templateStructure,
  search,
  children,
}) => {
  const [showRawView, setShowRawView] = useState(false);

  const messages = useMemo<ChatMessage[]>(
    () => parseMessagesFromTemplate(template),
    [template],
  );

  const hasMessages = messages.length > 0;

  // Infer type when templateStructure is not explicitly set
  const isChatPrompt =
    templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT ||
    (templateStructure === undefined && hasMessages);
  const isTextPrompt =
    templateStructure === PROMPT_TEMPLATE_STRUCTURE.TEXT ||
    (templateStructure === undefined && !hasMessages);
  const showToggle = (isChatPrompt && hasMessages) || isTextPrompt;

  const parsedTemplate = useMemo(() => {
    try {
      return typeof template === "string" ? JSON.parse(template) : template;
    } catch {
      return template;
    }
  }, [template]);

  const textContent = useMemo(() => {
    if (typeof template === "string") {
      return template;
    }
    return JSON.stringify(template, null, 2);
  }, [template]);

  const renderToggleButton = () => {
    if (!showToggle) return null;

    if (isChatPrompt) {
      return (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setShowRawView(!showRawView)}
        >
          {showRawView ? (
            <>
              <MessageSquare className="mr-1.5 size-3.5" />
              Message view
            </>
          ) : (
            <>
              <Code2 className="mr-1.5 size-3.5" />
              Raw view
            </>
          )}
        </Button>
      );
    }

    return (
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setShowRawView(!showRawView)}
      >
        {showRawView ? (
          <>
            <Type className="mr-1.5 size-3.5" />
            Pretty view
          </>
        ) : (
          <>
            <Code2 className="mr-1.5 size-3.5" />
            Raw view
          </>
        )}
      </Button>
    );
  };

  const renderContent = () => {
    if (showRawView) {
      return (
        <SyntaxHighlighter
          withSearch={Boolean(search)}
          data={parsedTemplate as object}
          search={search}
        />
      );
    }

    if (isChatPrompt && hasMessages) {
      return <PromptMessagesReadonly messages={messages} />;
    }

    if (isTextPrompt) {
      return (
        <div
          className="comet-body-s whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-3 text-foreground"
          data-testid="prompt-text-content"
        >
          {textContent}
        </div>
      );
    }

    return (
      <SyntaxHighlighter
        withSearch={Boolean(search)}
        data={parsedTemplate as object}
        search={search}
      />
    );
  };

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between">
        <div className="comet-body-s-accented">
          {isChatPrompt ? "Chat messages" : "Prompt"}
        </div>
        {renderToggleButton()}
      </div>

      {renderContent()}

      {children}
    </div>
  );
};

export default PromptTemplateView;
