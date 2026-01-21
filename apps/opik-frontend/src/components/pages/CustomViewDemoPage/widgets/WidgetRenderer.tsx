import React from "react";
import { WidgetType, WidgetProps } from "@/types/custom-view";
import SimpleWidget from "./SimpleWidget";
import CodeWidget from "./CodeWidget";
import LinkWidget from "./LinkWidget";
import ImageWidget from "./ImageWidget";
import VideoWidget from "./VideoWidget";
import AudioWidget from "./AudioWidget";
import PdfWidget from "./PdfWidget";
import FileWidget from "./FileWidget";

interface WidgetRendererProps extends WidgetProps {
  type: WidgetType;
}

const WidgetRenderer: React.FC<WidgetRendererProps> = ({
  type,
  value,
  label,
  path,
}) => {
  switch (type) {
    case WidgetType.TEXT:
    case WidgetType.NUMBER:
    case WidgetType.BOOLEAN:
      return <SimpleWidget value={value} label={label} path={path} />;
    case WidgetType.CODE:
      return <CodeWidget value={value} label={label} path={path} />;
    case WidgetType.LINK:
      return <LinkWidget value={value} label={label} path={path} />;
    case WidgetType.IMAGE:
      return <ImageWidget value={value} label={label} path={path} />;
    case WidgetType.VIDEO:
      return <VideoWidget value={value} label={label} path={path} />;
    case WidgetType.AUDIO:
      return <AudioWidget value={value} label={label} path={path} />;
    case WidgetType.PDF:
      return <PdfWidget value={value} label={label} path={path} />;
    case WidgetType.FILE:
      return <FileWidget value={value} label={label} path={path} />;
    default:
      // Fallback to simple widget for unknown types
      return <SimpleWidget value={value} label={label} path={path} />;
  }
};

export default WidgetRenderer;
