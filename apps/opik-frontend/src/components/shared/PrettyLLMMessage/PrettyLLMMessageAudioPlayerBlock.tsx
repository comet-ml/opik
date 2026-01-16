import React, { useMemo } from "react";
import { cn } from "@/lib/utils";
import AudioPlayer from "@/components/shared/AudioPlayer/AudioPlayer";
import { PrettyLLMMessageAudioPlayerBlockProps } from "./types";
import { useMediaResolver } from "@/hooks/useMediaResolver";

/**
 * Pure presentation component for displaying audio in LLM messages.
 * Resolves placeholders like "[audio_0]" using MediaContext.
 * No API calls - all data comes from context.
 */
const PrettyLLMMessageAudioPlayerBlock: React.FC<
  PrettyLLMMessageAudioPlayerBlockProps
> = ({ audios, url, name, className }) => {
  const resolveMedia = useMediaResolver();

  // Resolve placeholders to actual URLs using centralized resolver
  const resolvedAudioList = useMemo(() => {
    // Support both array and single audio formats
    const audioList = audios || (url ? [{ url, name: name || "Audio" }] : []);

    return audioList.map((audio) => {
      const resolved = resolveMedia(audio.url, audio.name);
      return {
        url: resolved.url,
        name: resolved.name,
      };
    });
  }, [audios, url, name, resolveMedia]);

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
