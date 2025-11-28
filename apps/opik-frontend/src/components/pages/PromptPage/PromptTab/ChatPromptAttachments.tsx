import React, { useMemo } from "react";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/components/shared/CodeHighlighter/CodeHighlighter";

interface ChatPromptAttachmentsProps {
  attachments: Array<{ type: string; [key: string]: unknown }>;
}

const ChatPromptAttachments: React.FC<ChatPromptAttachmentsProps> = ({
  attachments,
}) => {
  // Memoize the stringified attachments to avoid costly JSON.stringify on every render
  const stringifiedAttachments = useMemo(
    () => attachments.map((attachment) => JSON.stringify(attachment, null, 2)),
    [attachments],
  );

  if (attachments.length === 0) {
    return null;
  }

  return (
    <div className="space-y-2">
      {stringifiedAttachments.map((stringifiedAttachment, index) => (
        <div key={index} className="rounded border">
          <CodeHighlighter
            data={stringifiedAttachment}
            language={SUPPORTED_LANGUAGE.json}
          />
        </div>
      ))}
    </div>
  );
};

export default ChatPromptAttachments;
