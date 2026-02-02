import React from "react";
import AudioPlayerLib from "react-h5-audio-player";
import "react-h5-audio-player/lib/styles.css";
import { cn } from "@/lib/utils";
import { Play, Pause, Loader2, AlertCircle } from "lucide-react";
import { useAudioPlayer } from "@/hooks/useAudioPlayer";

export interface AudioPlayerProps {
  url: string;
  name?: string;
  className?: string;
}

const AudioPlayer: React.FC<AudioPlayerProps> = ({ url, name, className }) => {
  const {
    duration,
    currentTime,
    isPlaying,
    isLoading,
    hasError,
    errorMessage,
    audioRef,
    formatTime,
  } = useAudioPlayer({ url });

  return (
    <div
      className={cn("rounded-md border p-2 bg-primary-foreground", className)}
    >
      {/* Header: Filename and Duration */}
      <div className="mb-1 flex items-center justify-between">
        <span className="truncate text-xs" style={{ color: "#45575F" }}>
          {name || "Audio"}
        </span>
        {hasError ? (
          <span className="ml-2 shrink-0 text-xs text-slate-500">
            {errorMessage}
          </span>
        ) : (
          <span className="ml-2 shrink-0 text-xs text-light-slate">
            {formatTime(currentTime)} /{" "}
            {duration > 0 ? formatTime(duration) : "00:00"}
          </span>
        )}
      </div>

      {/* Custom Audio Player */}
      <div className="flex items-center gap-1">
        {/* Play/Pause/Loading/Error Icon */}
        <div className="flex size-8 shrink-0 items-center justify-center">
          {hasError ? (
            <AlertCircle
              className="size-3.5 text-slate-400"
              aria-label="Audio error"
            />
          ) : isLoading ? (
            <Loader2 className="size-3.5 animate-spin text-light-slate" />
          ) : isPlaying ? (
            <button
              onClick={() => audioRef.current?.audio.current?.pause()}
              className="flex size-8 items-center justify-center rounded-full hover:bg-accent"
              aria-label="Pause"
            >
              <Pause className="size-3.5 text-light-slate" />
            </button>
          ) : (
            <button
              onClick={() => audioRef.current?.audio.current?.play()}
              className="flex size-8 items-center justify-center rounded-full hover:bg-accent"
              aria-label="Play"
            >
              <Play className="size-3.5 text-light-slate" />
            </button>
          )}
        </div>

        {/* Hidden Audio Player (we use it for functionality but hide its UI) */}
        <div className="hidden">
          <AudioPlayerLib
            ref={audioRef}
            src={url}
            showJumpControls={false}
            customAdditionalControls={[]}
            customVolumeControls={[]}
            layout="horizontal-reverse"
          />
        </div>

        {/* Progress Bar */}
        <div className="relative flex-1">
          <div
            className={cn(
              "h-[6px] w-full overflow-hidden rounded-full",
              hasError ? "bg-gray-100" : "bg-gray-200",
            )}
          >
            <div
              className="h-full rounded-full bg-indigo-600 transition-all"
              style={{
                width:
                  duration > 0 ? `${(currentTime / duration) * 100}%` : "0%",
              }}
            />
          </div>
          {/* Clickable overlay for seeking */}
          <input
            type="range"
            min="0"
            max={duration || 0}
            value={currentTime}
            onChange={(e) => {
              const audio = audioRef.current?.audio.current;
              if (audio) {
                audio.currentTime = Number(e.target.value);
              }
            }}
            className="absolute inset-0 size-full cursor-pointer opacity-0"
            aria-label="Seek"
            disabled={hasError}
          />
        </div>
      </div>
    </div>
  );
};

export default AudioPlayer;
