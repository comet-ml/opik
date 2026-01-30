import React, { useState } from "react";
import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
} from "lucide-react";
import { AgentOptimizerMessage, UserResponse } from "@/types/agent-optimizer";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Alert, AlertDescription } from "@/components/ui/alert";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";

// Helper component for expandable text content
const ExpandableText: React.FC<{
  text: string;
  maxLength?: number;
  className?: string;
  preformatted?: boolean;
}> = ({ text, maxLength = 300, className = "", preformatted = false }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const needsTruncation = text.length > maxLength;
  const displayText =
    isExpanded || !needsTruncation
      ? text
      : text.substring(0, maxLength) + "...";

  const content = preformatted ? (
    <pre className={`whitespace-pre-wrap break-words ${className}`}>
      {displayText}
    </pre>
  ) : (
    <div className={`whitespace-pre-wrap break-words ${className}`}>
      {displayText}
    </div>
  );

  if (!needsTruncation) {
    return content;
  }

  return (
    <div>
      {content}
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className="mt-1 flex items-center gap-1 text-xs text-primary hover:underline"
      >
        {isExpanded ? (
          <>
            <ChevronUp className="size-3" />
            Show less
          </>
        ) : (
          <>
            <ChevronDown className="size-3" />
            Show more
          </>
        )}
      </button>
    </div>
  );
};

interface OptimizerChatMessageProps {
  message: AgentOptimizerMessage;
  onRespond?: (response: UserResponse) => void;
  /** Whether this is the last message in the list - used to determine if progress messages should show as completed */
  isLastMessage?: boolean;
}

const OptimizerChatMessage: React.FC<OptimizerChatMessageProps> = ({
  message,
  onRespond,
  isLastMessage = false,
}) => {
  const [inputValue, setInputValue] = useState("");
  const [assertions, setAssertions] = useState<string[]>([""]);
  const [selectedOption, setSelectedOption] = useState<string>("");
  const [expandedPrompts, setExpandedPrompts] = useState<Set<number>>(
    new Set(),
  );

  const togglePromptExpanded = (idx: number) => {
    setExpandedPrompts((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) {
        next.delete(idx);
      } else {
        next.add(idx);
      }
      return next;
    });
  };

  const handleSubmit = () => {
    if (!onRespond) return;

    if (message.type === "user_input_request") {
      if (message.inputSubtype === "assertions") {
        const validAssertions = assertions.filter((a) => a.trim() !== "");
        if (validAssertions.length > 0) {
          onRespond({
            responseType: "assertions",
            data: validAssertions,
          });
        }
      } else if (message.inputSubtype === "agent_endpoint") {
        onRespond({
          responseType: "agent_endpoint",
          data: inputValue,
        });
      } else if (message.inputSubtype === "choice") {
        onRespond({
          responseType: "choice",
          data: inputValue,
        });
      } else if (message.inputSubtype === "text_input") {
        onRespond({
          responseType: "text_input",
          data: inputValue,
        });
      } else if (message.inputSubtype === "confirmation") {
        onRespond({
          responseType: "confirmation",
          data: inputValue,
        });
      }
    } else if (message.type === "options_menu" && selectedOption) {
      onRespond({
        responseType: "menu_selection",
        data: selectedOption,
      });
    }
  };

  // Render based on message type
  if (message.type === "header") {
    return (
      <div className="my-4">
        <h2 className="border-b-2 pb-2 text-2xl font-bold">
          {message.content}
        </h2>
      </div>
    );
  }

  if (message.type === "system_message") {
    return (
      <div className="my-2 whitespace-pre-wrap text-sm text-muted-foreground">
        {message.content}
      </div>
    );
  }

  if (message.type === "success_message") {
    return (
      <Alert className="my-2 border-green-500 bg-green-50">
        <CheckCircle2 className="size-4 text-green-600" />
        <AlertDescription className="text-green-800">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "error") {
    return (
      <Alert className="my-2 border-red-500 bg-red-50">
        <XCircle className="size-4 text-red-600" />
        <AlertDescription className="text-red-800">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "warning") {
    return (
      <Alert className="my-2 border-yellow-500 bg-yellow-50">
        <AlertTriangle className="size-4 text-yellow-600" />
        <AlertDescription className="whitespace-pre-wrap text-yellow-800">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "progress") {
    // Don't show progress messages if there are messages after this one
    // (meaning the operation completed and moved on)
    if (!isLastMessage) {
      return null;
    }

    // Show spinner only for ongoing progress (last message)
    return (
      <div className="my-2 flex items-center gap-2 text-sm text-muted-foreground">
        <div className="size-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        <span>{message.content}</span>
      </div>
    );
  }

  if (message.type === "optimization_progress") {
    return (
      <div className="my-1 text-xs italic text-muted-foreground/70">
        {message.content}
      </div>
    );
  }

  if (message.type === "assertion_results" && message.assertionResults) {
    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        <div className="space-y-2">
          {message.assertionResults.map((result, idx) => (
            <div key={idx} className="flex items-start gap-2">
              {result.passed ? (
                <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-green-600" />
              ) : (
                <XCircle className="mt-0.5 size-5 shrink-0 text-red-600" />
              )}
              <div className="flex-1">
                <div className="font-medium">{result.assertion}</div>
                <div className="text-sm text-muted-foreground">
                  {result.reason}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (message.type === "diff_view" && message.changes) {
    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        <div className="space-y-4">
          {message.changes.map((change) => (
            <div key={change.id} className="rounded border p-3">
              <div className="mb-2 font-medium">
                [{change.id}] {change.promptName} - {change.changeType}
              </div>
              <div className="overflow-x-auto font-mono text-sm">
                <TextDiff
                  content1={change.originalContent}
                  content2={change.modifiedContent}
                  mode="lines"
                />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (message.type === "trace_info") {
    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        {message.traceData ? (
          <div className="space-y-4 text-sm">
            {/* Prompts section first since the title mentions prompts */}
            {message.traceData.prompts &&
              message.traceData.prompts.length > 0 && (
                <div>
                  <div className="mb-2 font-medium">Prompts:</div>
                  <div className="space-y-2">
                    {message.traceData.prompts.map((p, idx) => (
                      <div key={idx} className="rounded border bg-muted/50 p-3">
                        <button
                          onClick={() => togglePromptExpanded(idx)}
                          className="flex w-full items-center justify-between text-left"
                        >
                          <span className="font-medium">
                            {p.name}{" "}
                            <span className="text-xs text-muted-foreground">
                              ({p.type})
                            </span>
                          </span>
                          {expandedPrompts.has(idx) ? (
                            <ChevronUp className="size-4 text-muted-foreground" />
                          ) : (
                            <ChevronDown className="size-4 text-muted-foreground" />
                          )}
                        </button>
                        {expandedPrompts.has(idx) && (
                          <div className="mt-2 whitespace-pre-wrap rounded bg-background p-2 text-xs text-muted-foreground">
                            {p.preview}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

            {/* Input section */}
            <div>
              <div className="mb-1 font-medium">Input:</div>
              <div className="rounded bg-muted p-2">
                <ExpandableText
                  text={JSON.stringify(message.traceData.input_data, null, 2)}
                  maxLength={500}
                  preformatted
                  className="text-xs"
                />
              </div>
            </div>

            {/* Output section */}
            <div>
              <div className="mb-1 font-medium">Output:</div>
              <div className="rounded bg-muted p-2">
                <ExpandableText
                  text={message.traceData.final_output}
                  maxLength={500}
                  className="text-xs"
                />
              </div>
            </div>
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">
            Loading trace data...
          </div>
        )}
      </div>
    );
  }

  // For options_menu messages:
  // - If it's the last message AND waitingForResponse is true -> show the menu
  // - Otherwise -> hide (user already selected an option)
  if (message.type === "options_menu" && message.options) {
    const shouldShowMenu = message.waitingForResponse && isLastMessage;

    if (!shouldShowMenu) {
      return null;
    }

    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        <div className="space-y-2">
          {message.options.map((option) => (
            <Button
              key={option.id}
              variant={selectedOption === option.id ? "default" : "outline"}
              className="w-full justify-start"
              onClick={() => {
                setSelectedOption(option.id);
                if (onRespond) {
                  onRespond({
                    responseType: "menu_selection",
                    data: option.id,
                  });
                }
              }}
            >
              {option.label}
            </Button>
          ))}
        </div>
      </div>
    );
  }

  // For user_input_request messages:
  // - If it's the last message AND waitingForResponse is true -> show the input form
  // - Otherwise -> hide (user already responded)
  if (message.type === "user_input_request") {
    const shouldShowInput = message.waitingForResponse && isLastMessage;

    // Hide after user has responded
    if (!shouldShowInput) {
      return null;
    }

    if (message.inputSubtype === "assertions") {
      return (
        <div className="my-4 rounded-lg border p-4">
          <h3 className="mb-3 font-semibold">{message.content}</h3>
          <div className="space-y-2">
            {assertions.map((assertion, idx) => (
              <div key={idx} className="flex gap-2">
                <Input
                  value={assertion}
                  onChange={(e) => {
                    const newAssertions = [...assertions];
                    newAssertions[idx] = e.target.value;
                    setAssertions(newAssertions);
                  }}
                  placeholder={`Assertion ${idx + 1}`}
                />
                {assertions.length > 1 && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => {
                      setAssertions(assertions.filter((_, i) => i !== idx));
                    }}
                  >
                    Remove
                  </Button>
                )}
              </div>
            ))}
            <div className="flex gap-2">
              <Button
                variant="outline"
                onClick={() => setAssertions([...assertions, ""])}
              >
                Add Assertion
              </Button>
              <Button onClick={handleSubmit}>Submit</Button>
            </div>
          </div>
        </div>
      );
    }

    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        {message.inputSubtype === "confirmation" ? (
          <div className="flex gap-2">
            <Button
              onClick={() => {
                setInputValue("y");
                onRespond?.({ responseType: "confirmation", data: "y" });
              }}
            >
              Yes
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setInputValue("n");
                onRespond?.({ responseType: "confirmation", data: "n" });
              }}
            >
              No
            </Button>
          </div>
        ) : (
          <div className="flex gap-2">
            <Input
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder={
                message.inputSubtype === "choice"
                  ? "Enter your choice (e.g., a, t1,2, s1, v1, n)"
                  : message.inputSubtype === "agent_endpoint"
                    ? "http://localhost:8001/chat"
                    : "Enter value"
              }
            />
            <Button onClick={handleSubmit}>Submit</Button>
          </div>
        )}
      </div>
    );
  }

  // Default rendering
  return (
    <div className="my-2 rounded-lg border p-3">
      <div className="text-sm text-muted-foreground">{message.content}</div>
    </div>
  );
};

export default OptimizerChatMessage;
