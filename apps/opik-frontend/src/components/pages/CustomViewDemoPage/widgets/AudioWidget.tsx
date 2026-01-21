import React from "react";
import ReactPlayer from "react-player";
import { WidgetProps } from "@/types/custom-view";

const AudioWidget: React.FC<WidgetProps> = ({ value, label }) => {
  if (value === null || value === undefined) {
    return (
      <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
        <div className="comet-body-s-accented mb-2">{label}</div>
        <div className="text-sm text-muted-slate">No data</div>
      </div>
    );
  }

  const audioUrl = String(value);
  const isDataUrl = audioUrl.startsWith("data:");

  return (
    <div className="rounded-lg border border-border bg-white p-4 shadow-sm">
      <div className="comet-body-s-accented mb-3">{label}</div>
      <div className="w-full">
        {isDataUrl ? (
          <audio src={audioUrl} controls className="w-full">
            Your browser does not support embedded audio.
          </audio>
        ) : (
          <ReactPlayer url={audioUrl} width="100%" height="50px" controls />
        )}
      </div>
    </div>
  );
};

export default AudioWidget;
