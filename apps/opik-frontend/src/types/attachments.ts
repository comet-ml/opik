export enum ATTACHMENT_TYPE {
  IMAGE = "image",
  VIDEO = "video",
  AUDIO = "audio",
  PDF = "pdf",
  TEXT = "text",
  OTHER = "other",
}

export type AttachmentEntityType = "span" | "trace";

export interface Attachment {
  link: string;
  file_name: string;
  file_size: number;
  mime_type: string;
}

export interface AttachmentWithType extends Attachment {
  type: ATTACHMENT_TYPE;
}

export type ParsedImageData = {
  url: string;
  name: string;
};

export type AttachmentPreviewData = {
  type: ATTACHMENT_TYPE;
  name: string;
  url: string;
};
