import React from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";

import { Description } from "@/components/ui/description";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { LLMMessage } from "@/types/llm";

const CODEMIRROR_BASIC_SETUP = {
  lineNumbers: true,
  highlightActiveLineGutter: false,
  highlightActiveLine: false,
  foldGutter: true,
};

interface ChatPromptRawViewProps {
  value: string;
  onMessagesChange: (messages: LLMMessage[]) => void;
  onRawValueChange: (value: string) => void;
}

const ChatPromptRawView: React.FC<ChatPromptRawViewProps> = ({
  value,
  onMessagesChange,
  onRawValueChange,
}) => {
  const theme = useCodemirrorTheme({
    editable: true,
  });

  return (
    <>
      <div className="max-h-[400px] overflow-y-auto rounded-md border">
        <CodeMirror
          theme={theme}
          value={value}
          onChange={(newValue) => {
            // Update the raw value immediately for smooth typing
            onRawValueChange(newValue);

            // Try to parse and update messages only if valid
            try {
              const parsed = JSON.parse(newValue);
              if (Array.isArray(parsed)) {
                // Validate that all messages have role field
                const allValid = parsed.every(
                  (msg) =>
                    msg &&
                    typeof msg === "object" &&
                    typeof msg.role === "string",
                );

                if (allValid) {
                  onMessagesChange(
                    parsed.map((msg, index) => ({
                      id: `msg-${index}`,
                      role: msg.role,
                      content: msg.content,
                    })),
                  );
                }
              }
            } catch {
              // Invalid JSON, don't update messages
            }
          }}
          extensions={[jsonLanguage, EditorView.lineWrapping]}
          basicSetup={CODEMIRROR_BASIC_SETUP}
        />
      </div>
      <Description>
        Edit the raw JSON representation of chat messages. Must be a valid JSON
        array with objects containing required &quot;role&quot; and
        &quot;content&quot; fields.
      </Description>
    </>
  );
};

export default ChatPromptRawView;
