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

export type ParsedVideoData = {
  url: string;
  name: string;
  /**
   * Optional mime type hint for rendering (e.g. video/mp4).
   * When provided, consumers can display or transform the video appropriately.
   */
  mimeType?: string;
};

export type ParsedAudioData = {
  url: string;
  name: string;
  /**
   * Optional mime type hint for rendering (e.g. audio/mpeg).
   * When provided, consumers can display or transform the audio appropriately.
   */
  mimeType?: string;
};

export type ParsedMediaData =
  | (ParsedImageData & { type: ATTACHMENT_TYPE.IMAGE })
  | (ParsedVideoData & { type: ATTACHMENT_TYPE.VIDEO })
  | (ParsedAudioData & { type: ATTACHMENT_TYPE.AUDIO });
