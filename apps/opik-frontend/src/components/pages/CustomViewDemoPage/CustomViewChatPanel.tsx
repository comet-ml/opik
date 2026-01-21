import React, { RefObject } from "react";
import { Send, Loader2, AlertCircle, RotateCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { ChatDisplayMessage } from "@/types/structured-completion";
import { cn } from "@/lib/utils";

interface CustomViewChatPanelProps {
  messages: ChatDisplayMessage[];
  inputValue: string;
  isLoading: boolean;
  error: string | null;
  onInputChange: (value: string) => void;
  onSend: () => void;
  onRetry: () => void;
  scrollContainerRef: RefObject<HTMLDivElement>;
  model: string | null | undefined;
  traceId: string | null | undefined;
}

const CustomViewChatPanel: React.FC<CustomViewChatPanelProps> = ({
  messages,
  inputValue,
  isLoading,
  error,
  onInputChange,
  onSend,
  onRetry,
  scrollContainerRef,
  model,
  traceId,
}) => {
  const canSend = Boolean(model && traceId && inputValue.trim() && !isLoading);

  const handleSend = () => {
    if (!canSend) return;
    onSend();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Messages */}
      <div
        className="flex-1 space-y-4 overflow-y-auto p-4"
        ref={scrollContainerRef}
      >
        {messages.length === 0 ? (
          <div className="flex h-full items-center justify-center text-center">
            <div className="max-w-md">
              <div className="comet-title-m mb-2 text-muted-slate">
                Start a conversation
              </div>
              <div className="comet-body-s text-muted-slate">
                Select a model and trace, then ask questions about the trace
                data.
              </div>
            </div>
          </div>
        ) : (
          <>
            {messages.map((message) => {
              const isUser = message.role === "user";
              const noContent = message.content === "";

              return (
                <div
                  key={message.id}
                  className={cn(
                    "mb-2 flex",
                    isUser ? "justify-end" : "justify-start",
                  )}
                >
                  <div
                    className={cn(
                      "relative min-w-[20%] max-w-[85%] rounded-lg px-4 py-3",
                      isUser
                        ? "bg-primary text-primary-foreground"
                        : "bg-muted",
                      message.isError &&
                        "border border-destructive bg-destructive/10",
                      noContent && "w-4/5",
                    )}
                  >
                    {message.isLoading && noContent ? (
                      <div className="flex w-full flex-wrap gap-2 overflow-hidden">
                        <Skeleton className="inline-block h-2 w-1/4" />
                        <Skeleton className="inline-block h-2 w-2/3" />
                        <Skeleton className="inline-block h-2 w-3/4" />
                        <Skeleton className="inline-block h-2 w-1/4" />
                      </div>
                    ) : isUser ? (
                      <div className="whitespace-pre-wrap">
                        {message.content}
                      </div>
                    ) : (
                      <MarkdownPreview
                        className={cn(message.isError && "text-destructive")}
                      >
                        {message.content}
                      </MarkdownPreview>
                    )}
                  </div>
                </div>
              );
            })}
            {error && !isLoading && (
              <div className="mb-2 flex justify-start">
                <div className="relative min-w-[20%] max-w-[85%] rounded-lg border border-destructive bg-destructive/10 px-4 py-3">
                  <div className="mb-3 flex items-start gap-2">
                    <AlertCircle className="mt-0.5 size-5 shrink-0 text-destructive" />
                    <div className="flex-1">
                      <div className="comet-body-s-accented mb-1 text-destructive">
                        Failed to generate visualization
                      </div>
                      <div className="comet-body-s text-destructive/80">
                        {error}
                      </div>
                    </div>
                  </div>
                  <Button
                    onClick={onRetry}
                    variant="outline"
                    size="sm"
                    className="w-full border-destructive/30 text-destructive hover:bg-destructive/10 hover:text-destructive"
                  >
                    <RotateCw className="mr-2 size-4" />
                    Retry
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* Input - Sticky to bottom */}
      <div className="mr-4 border-t p-2 py-4">
        <div className="flex gap-2">
          <Textarea
            value={inputValue}
            onChange={(e) => onInputChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={
              !model || !traceId
                ? "Select a model and trace to start chatting..."
                : isLoading
                  ? "Waiting for response..."
                  : "Type your message..."
            }
            disabled={!model || !traceId || isLoading}
            className="max-h-[200px] min-h-[60px] resize-none"
          />
          <Button
            onClick={handleSend}
            disabled={!canSend}
            size="icon"
            className="shrink-0"
          >
            {isLoading ? (
              <Loader2 className="size-4 animate-spin" />
            ) : (
              <Send className="size-4" />
            )}
          </Button>
        </div>
        <div className="mt-2 text-xs text-muted-slate">
          Press Cmd/Ctrl + Enter to send
        </div>
      </div>
    </div>
  );
};

export default CustomViewChatPanel;
