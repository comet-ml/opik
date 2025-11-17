import React from "react";
import ReactPlayer from "react-player";

import { ATTACHMENT_TYPE } from "@/types/attachments";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import PDFPreview from "@/components/pages-shared/attachments/PDFPreview/PDFPreview";
import TextPreview from "@/components/pages-shared/attachments/TextPreview/TextPreview";
import ZoomPanContainer from "@/components/shared/ZoomPanContainer/ZoomPanContainer";

export type AttachmentPreviewProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  type: ATTACHMENT_TYPE;
  name: string;
  url: string;
};

const AttachmentPreviewDialog: React.FC<AttachmentPreviewProps> = ({
  open,
  setOpen,
  type,
  name,
  url,
}) => {
  const containerClassName = () => {
    switch (type) {
      case ATTACHMENT_TYPE.VIDEO:
      case ATTACHMENT_TYPE.AUDIO:
        return "w-[800px]";
      case ATTACHMENT_TYPE.IMAGE:
      case ATTACHMENT_TYPE.PDF:
      case ATTACHMENT_TYPE.TEXT:
        return "w-[90vw]";
      case ATTACHMENT_TYPE.OTHER:
      default:
        return "";
    }
  };

  const renderContent = () => {
    if (!open) return null;

    switch (type) {
      case ATTACHMENT_TYPE.IMAGE:
        return renderImageContent();
      case ATTACHMENT_TYPE.VIDEO:
        return renderVideoContent();
      case ATTACHMENT_TYPE.AUDIO:
        return renderAudioContent();
      case ATTACHMENT_TYPE.PDF:
        return renderPdfContent();
      case ATTACHMENT_TYPE.TEXT:
        return renderTextContent();
      case ATTACHMENT_TYPE.OTHER:
      default:
        return null;
    }
  };

  const renderImageContent = () => {
    return (
      <div className="size-full h-[80vh] p-4">
        <ZoomPanContainer expandButton={false} className="pt-8">
          <img
            src={url}
            loading="lazy"
            alt={name}
            className="m-auto size-full object-contain"
          />
        </ZoomPanContainer>
      </div>
    );
  };

  const renderVideoContent = () => {
    const isDataUrl = url.startsWith("data:");

    if (isDataUrl) {
      return (
        <div className="flex max-h-[80vh] max-w-[90vw] items-center justify-center">
          <video
            src={url}
            controls
            className="max-h-[80vh] max-w-full"
            preload="auto"
          >
            Your browser does not support embedded videos.
          </video>
        </div>
      );
    }

    return (
      <div className="flex items-center justify-center">
        <ReactPlayer playing url={url} controls />
      </div>
    );
  };

  const renderAudioContent = () => {
    return (
      <div className="flex items-center justify-center">
        <ReactPlayer playing url={url} controls height="100px" />
      </div>
    );
  };

  const renderPdfContent = () => {
    return (
      <div className="h-[80vh] w-full">
        <PDFPreview url={url}></PDFPreview>
      </div>
    );
  };

  const renderTextContent = () => {
    return (
      <div className="h-[80vh] w-full">
        <TextPreview url={url}></TextPreview>
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className={containerClassName()}
        onEscapeKeyDown={(e) => e.stopPropagation()}
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle className="truncate">{name}</DialogTitle>
          {renderContent()}
        </DialogHeader>
      </DialogContent>
    </Dialog>
  );
};

export default AttachmentPreviewDialog;
