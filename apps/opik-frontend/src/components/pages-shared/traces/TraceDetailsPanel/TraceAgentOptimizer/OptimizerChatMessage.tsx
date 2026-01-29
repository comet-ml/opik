import React, { useState } from "react";
import { CheckCircle2, XCircle, AlertTriangle } from "lucide-react";
import { AgentOptimizerMessage, UserResponse } from "@/types/agent-optimizer";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Input } from "@/components/ui/input";
import { Alert, AlertDescription } from "@/components/ui/alert";

interface OptimizerChatMessageProps {
  message: AgentOptimizerMessage;
  onRespond?: (response: UserResponse) => void;
}

const OptimizerChatMessage: React.FC<OptimizerChatMessageProps> = ({
  message,
  onRespond,
}) => {
  const [inputValue, setInputValue] = useState("");
  const [assertions, setAssertions] = useState<string[]>([""]);
  const [selectedOption, setSelectedOption] = useState<string>("");

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
        <h2 className="text-2xl font-bold border-b-2 pb-2">{message.content}</h2>
      </div>
    );
  }

  if (message.type === "system_message") {
    return (
      <div className="my-2 text-sm text-muted-foreground whitespace-pre-wrap">
        {message.content}
      </div>
    );
  }

  if (message.type === "success_message") {
    return (
      <Alert className="my-2 border-green-500 bg-green-50">
        <CheckCircle2 className="h-4 w-4 text-green-600" />
        <AlertDescription className="text-green-800">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "error") {
    return (
      <Alert className="my-2 border-red-500 bg-red-50">
        <XCircle className="h-4 w-4 text-red-600" />
        <AlertDescription className="text-red-800">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "warning") {
    return (
      <Alert className="my-2 border-yellow-500 bg-yellow-50">
        <AlertTriangle className="h-4 w-4 text-yellow-600" />
        <AlertDescription className="text-yellow-800 whitespace-pre-wrap">
          {message.content}
        </AlertDescription>
      </Alert>
    );
  }

  if (message.type === "progress") {
    return (
      <div className="my-2 flex items-center gap-2 text-sm text-muted-foreground">
        <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        <span>{message.content}</span>
        {message.status && <span className="text-xs">({message.status})</span>}
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
                <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-green-600" />
              ) : (
                <XCircle className="mt-0.5 h-5 w-5 shrink-0 text-red-600" />
              )}
              <div className="flex-1">
                <div className="font-medium">{result.assertion}</div>
                <div className="text-sm text-muted-foreground">{result.reason}</div>
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
              <div className="space-y-1 text-sm font-mono">
                <div className="text-red-600">- {change.originalContent.substring(0, 100)}...</div>
                <div className="text-green-600">+ {change.modifiedContent.substring(0, 100)}...</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (message.type === "trace_info" && message.traceData) {
    return (
      <div className="my-4 rounded-lg border p-4">
        <h3 className="mb-3 font-semibold">{message.content}</h3>
        <div className="space-y-3 text-sm">
          <div>
            <div className="font-medium">Input:</div>
            <pre className="mt-1 overflow-auto rounded bg-muted p-2">
              {JSON.stringify(message.traceData.input_data, null, 2)}
            </pre>
          </div>
          <div>
            <div className="font-medium">Output:</div>
            <div className="mt-1 rounded bg-muted p-2">{message.traceData.final_output}</div>
          </div>
          <div>
            <div className="font-medium">Prompts ({message.traceData.prompts.length}):</div>
            <div className="mt-1 space-y-2">
              {message.traceData.prompts.map((p, idx) => (
                <div key={idx} className="rounded bg-muted p-2">
                  <div className="font-medium">{p.name} ({p.type})</div>
                  <div className="text-xs text-muted-foreground">{p.preview}</div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (message.type === "options_menu" && message.options) {
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
              disabled={!message.waitingForResponse}
            >
              {option.label}
            </Button>
          ))}
        </div>
      </div>
    );
  }

  if (message.type === "user_input_request" && message.waitingForResponse) {
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
            <Button onClick={() => {
              setInputValue("y");
              onRespond?.({ responseType: "confirmation", data: "y" });
            }}>
              Yes
            </Button>
            <Button variant="outline" onClick={() => {
              setInputValue("n");
              onRespond?.({ responseType: "confirmation", data: "n" });
            }}>
              No
            </Button>
          </div>
        ) : (
          <div className="flex gap-2">
            {message.inputSubtype === "choice" ? (
              <Textarea
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder="Enter your choice (e.g., a, t1,2, s1, v1, n)"
                rows={2}
              />
            ) : (
              <Input
                value={inputValue}
                onChange={(e) => setInputValue(e.target.value)}
                placeholder={
                  message.inputSubtype === "agent_endpoint"
                    ? "http://localhost:8001/chat (optional)"
                    : "Enter value"
                }
              />
            )}
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
