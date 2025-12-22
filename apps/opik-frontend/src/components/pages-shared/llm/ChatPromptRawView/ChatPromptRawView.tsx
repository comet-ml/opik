import React, { useState, useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";

import { Description } from "@/components/ui/description";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { LLMMessage, LLM_MESSAGE_ROLE } from "@/types/llm";

const CODEMIRROR_BASIC_SETUP = {
  lineNumbers: true,
  highlightActiveLineGutter: false,
  highlightActiveLine: false,
  foldGutter: true,
};

const VALID_ROLES = [
  LLM_MESSAGE_ROLE.system,
  LLM_MESSAGE_ROLE.user,
  LLM_MESSAGE_ROLE.assistant,
];

const isValidMessageContent = (content: unknown): boolean => {
  // Content can be a string
  if (typeof content === "string") {
    return true;
  }

  // Or an array of objects (for multimodal content)
  if (Array.isArray(content)) {
    return content.every(
      (item) => item && typeof item === "object" && "type" in item,
    );
  }

  return false;
};

const isValidMessage = (msg: unknown): boolean => {
  if (!msg || typeof msg !== "object") {
    return false;
  }

  const message = msg as Record<string, unknown>;

  // Must have a valid role
  if (!VALID_ROLES.includes(message.role as LLM_MESSAGE_ROLE)) {
    return false;
  }

  // Must have valid content
  if (!("content" in message) || !isValidMessageContent(message.content)) {
    return false;
  }

  return true;
};

interface ValidationResult {
  isValid: boolean;
  messages?: LLMMessage[];
}

export const validateAndParseJson = (jsonValue: string): ValidationResult => {
  if (!jsonValue) {
    return { isValid: false };
  }

  try {
    const parsed = JSON.parse(jsonValue);

    // Must be an array
    if (!Array.isArray(parsed)) {
      return { isValid: false };
    }

    // Must not be empty
    if (parsed.length === 0) {
      return { isValid: false };
    }

    // All messages must be valid
    if (!parsed.every(isValidMessage)) {
      return { isValid: false };
    }

    // Convert to LLMMessage format
    const messages: LLMMessage[] = parsed.map((msg, index) => ({
      id: `msg-${index}`,
      role: msg.role,
      content: msg.content,
    }));

    return { isValid: true, messages };
  } catch {
    // Invalid JSON
    return { isValid: false };
  }
};

interface ChatPromptRawViewProps {
  value: string;
  onMessagesChange: (messages: LLMMessage[]) => void;
  onRawValueChange: (value: string) => void;
  onValidationChange?: (isValid: boolean) => void;
}

const ChatPromptRawView: React.FC<ChatPromptRawViewProps> = ({
  value,
  onMessagesChange,
  onRawValueChange,
  onValidationChange,
}) => {
  const theme = useCodemirrorTheme({
    editable: true,
  });
  const [isValid, setIsValid] = useState(true);

  const processWithValidation = useCallback(
    (jsonValue: string) => {
      const result = validateAndParseJson(jsonValue);
      setIsValid(result.isValid);
      onValidationChange?.(result.isValid);

      if (result.isValid && result.messages) {
        onMessagesChange(result.messages);
      }
    },
    [onMessagesChange, onValidationChange],
  );

  return (
    <>
      <div className="max-h-[400px] overflow-y-auto rounded-md border">
        <CodeMirror
          theme={theme}
          value={value}
          onChange={(newValue) => {
            // Update the raw value immediately for smooth typing
            onRawValueChange(newValue);
            // Validate and update messages if valid
            processWithValidation(newValue);
          }}
          extensions={[jsonLanguage, EditorView.lineWrapping]}
          basicSetup={CODEMIRROR_BASIC_SETUP}
        />
      </div>
      {!isValid && (
        <p className="mt-2 text-sm text-destructive" role="alert">
          Message format is invalid.
        </p>
      )}
      <Description>
        Edit the raw JSON representation of chat messages. Must be a valid JSON
        array with at least one object. Each object must have a &quot;role&quot;
        field (system/user/assistant) and a &quot;content&quot; field (string or
        array of objects).
      </Description>
    </>
  );
};

export default ChatPromptRawView;
