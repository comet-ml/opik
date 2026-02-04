import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import AudioPlayer from "@/components/shared/AudioPlayer/AudioPlayer";
import { PrettyLLMMessageAudioPlayerBlockProps } from "./types";
import { useMediaContext } from "@/components/shared/PrettyLLMMessage/llmMessages/MediaContext";

/**
 * Pure presentation component for displaying audio in LLM messages.
 * Resolves placeholders like "[audio_0]" or "[output-attachment-1-xxx.wav]" using MediaContext.
 * No API calls - all data comes from context.
 */
const PrettyLLMMessageAudioPlayerBlock: React.FC<
  PrettyLLMMessageAudioPlayerBlockProps
> = ({ audios, className }) => {
  const { resolveMedia } = useMediaContext();

  // Resolve placeholders to actual URLs using centralized resolver
  const resolvedAudioList = useMemo(() => {
    return audios.map((audio) => {
      const resolved = resolveMedia(audio.url, audio.name);

      return {
        url: resolved.url,
        name: resolved.name,
      };
    });
  }, [audios, resolveMedia]);

  if (resolvedAudioList.length === 0) {
    return null;
  }

  // Single audio - render without grid
  if (resolvedAudioList.length === 1) {
    return (
      <AudioPlayer
        url={resolvedAudioList[0].url}
        name={resolvedAudioList[0].name}
        className={className}
      />
    );
  }

  // Multiple audios - render in grid layout
  return (
    <div className={cn("grid grid-cols-1 gap-2 sm:grid-cols-2", className)}>
      {resolvedAudioList.map((audio, index) => (
        <AudioPlayer key={index} url={audio.url} name={audio.name} />
      ))}
    </div>
  );
};

export default PrettyLLMMessageAudioPlayerBlock;
