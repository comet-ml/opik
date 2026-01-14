import React from "react";
import { cn } from "@/lib/utils";
import AudioPlayer from "@/components/shared/AudioPlayer/AudioPlayer";
import { PrettyLLMMessageAudioPlayerBlockProps } from "./types";

const PrettyLLMMessageAudioPlayerBlock: React.FC<
  PrettyLLMMessageAudioPlayerBlockProps
> = ({ audios, url, name, className }) => {
  // Support both array and single audio formats
  const audioList = audios || (url ? [{ url, name: name || "Audio" }] : []);

  if (audioList.length === 0) {
    return null;
  }

  // Single audio - render without grid
  if (audioList.length === 1) {
    return (
      <AudioPlayer
        url={audioList[0].url}
        name={audioList[0].name}
        className={className}
      />
    );
  }

  // Multiple audios - render in grid layout
  return (
    <div className={cn("grid grid-cols-1 gap-2 sm:grid-cols-2", className)}>
      {audioList.map((audio, index) => (
        <AudioPlayer key={index} url={audio.url} name={audio.name} />
      ))}
    </div>
  );
};

export default PrettyLLMMessageAudioPlayerBlock;
