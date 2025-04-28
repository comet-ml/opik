import {
  File,
  FileAudio,
  FileImage,
  FileText,
  FileVideo,
  LucideProps,
} from "lucide-react";
import FilePDF from "@/icons/file-pdf.svg?react";
import { ATTACHMENT_TYPE } from "@/types/attachments";
import * as react from "react";

export const MINE_TYPE_TO_ATTACHMENT_TYPE_MAP: Record<string, ATTACHMENT_TYPE> =
  {
    "application/pdf": ATTACHMENT_TYPE.PDF,
    "image/jpeg": ATTACHMENT_TYPE.IMAGE,
    "image/png": ATTACHMENT_TYPE.IMAGE,
    "image/gif": ATTACHMENT_TYPE.IMAGE,
    "image/svg+xml": ATTACHMENT_TYPE.IMAGE,
    "video/mp4": ATTACHMENT_TYPE.VIDEO,
    "video/webm": ATTACHMENT_TYPE.VIDEO,
    "audio/vorbis": ATTACHMENT_TYPE.AUDIO,
    "audio/wav": ATTACHMENT_TYPE.AUDIO,
    "audio/x-wav": ATTACHMENT_TYPE.AUDIO,
    "text/plain": ATTACHMENT_TYPE.TEXT,
    "text/markdown": ATTACHMENT_TYPE.TEXT,
    "application/json": ATTACHMENT_TYPE.TEXT,
    "application/octet-stream": ATTACHMENT_TYPE.OTHER,
  };

export const ATTACHMENT_ORDER_MAP: Record<ATTACHMENT_TYPE, number> = {
  [ATTACHMENT_TYPE.PDF]: 0,
  [ATTACHMENT_TYPE.TEXT]: 1,
  [ATTACHMENT_TYPE.OTHER]: 2,
  [ATTACHMENT_TYPE.AUDIO]: 3,
  [ATTACHMENT_TYPE.VIDEO]: 4,
  [ATTACHMENT_TYPE.IMAGE]: 5,
};

export const ATTACHMENT_ICON_MAP: Record<
  ATTACHMENT_TYPE,
  react.ForwardRefExoticComponent<
    Omit<LucideProps, "ref"> & react.RefAttributes<SVGSVGElement>
  >
> = {
  [ATTACHMENT_TYPE.PDF]: FilePDF as react.ForwardRefExoticComponent<
    Omit<LucideProps, "ref"> & react.RefAttributes<SVGSVGElement>
  >,
  [ATTACHMENT_TYPE.TEXT]: FileText,
  [ATTACHMENT_TYPE.OTHER]: File,
  [ATTACHMENT_TYPE.AUDIO]: FileAudio,
  [ATTACHMENT_TYPE.VIDEO]: FileVideo,
  [ATTACHMENT_TYPE.IMAGE]: FileImage,
};
