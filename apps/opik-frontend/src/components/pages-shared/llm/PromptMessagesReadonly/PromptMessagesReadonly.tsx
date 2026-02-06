import React, { useMemo } from "react";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";

interface ChatMessage {
  role: string;
  content: string | Array<{ type: string; [key: string]: unknown }>;
}

interface PromptMessageCardProps {
  message: ChatMessage;
}

type MediaItem = {
  type: "image_url" | "video_url" | "audio_url" | "input_audio" | "text";
  url?: string;
  text?: string;
  image_url?: { url: string };
  video_url?: { url: string };
  audio_url?: { url: string };
  input_audio?: { data: string };
};

const getRoleLabel = (role: string): string => {
  const roleKey = role.toUpperCase() as keyof typeof LLM_MESSAGE_ROLE;
  if (LLM_MESSAGE_ROLE[roleKey]) {
    return LLM_MESSAGE_ROLE_NAME_MAP[LLM_MESSAGE_ROLE[roleKey]] || role;
  }
  return role.charAt(0).toUpperCase() + role.slice(1);
};

const getTextAndMedia = (
  content: string | Array<{ type: string; [key: string]: unknown }>,
): {
  text: string;
  images: string[];
  videos: string[];
  audios: string[];
} => {
  if (typeof content === "string") {
    return { text: content, images: [], videos: [], audios: [] };
  }

  if (Array.isArray(content)) {
    const textParts: string[] = [];
    const images: string[] = [];
    const videos: string[] = [];
    const audios: string[] = [];

    content.forEach((part) => {
      const item = part as MediaItem;
      if (item.type === "text" && item.text) {
        textParts.push(item.text);
      } else if (item.type === "image_url") {
        const url = item.image_url?.url || item.url;
        if (url) images.push(url);
      } else if (item.type === "video_url") {
        const url = item.video_url?.url || item.url;
        if (url) videos.push(url);
      } else if (item.type === "audio_url" || item.type === "input_audio") {
        const url = item.audio_url?.url || item.input_audio?.data || item.url;
        if (url) audios.push(url);
      }
    });

    return { text: textParts.join("\n"), images, videos, audios };
  }

  return { text: "", images: [], videos: [], audios: [] };
};

export const PromptMessageCard: React.FC<PromptMessageCardProps> = ({
  message,
}) => {
  const { text, images, videos, audios } = getTextAndMedia(message.content);
  const hasMedia = images.length > 0 || videos.length > 0 || audios.length > 0;

  return (
    <div className="flex flex-col gap-2.5 rounded-md border bg-primary-foreground p-3">
      <div className="flex items-center">
        <span className="comet-body-s-accented text-light-slate">
          {getRoleLabel(message.role)}
        </span>
      </div>
      {text && (
        <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
          {text}
        </div>
      )}
      {hasMedia && (
        <div className="flex flex-wrap items-center gap-1.5">
          <MediaTagsList type="image" items={images} editable={false} />
          <MediaTagsList type="video" items={videos} editable={false} />
          <MediaTagsList type="audio" items={audios} editable={false} />
        </div>
      )}
    </div>
  );
};

interface PromptMessagesReadonlyProps {
  template: string | object;
}

const PromptMessagesReadonly: React.FC<PromptMessagesReadonlyProps> = ({
  template,
}) => {
  const messages = useMemo<ChatMessage[]>(() => {
    try {
      const data =
        typeof template === "string" ? JSON.parse(template) : template;
      return Array.isArray(data) ? data : [];
    } catch {
      return [];
    }
  }, [template]);

  if (messages.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-col gap-2">
      {messages.map((message, index) => (
        <PromptMessageCard key={index} message={message} />
      ))}
    </div>
  );
};

export default PromptMessagesReadonly;
