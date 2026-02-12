import {
  File,
  FileAudio,
  FileImage,
  FileSpreadsheet,
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
    "audio/vnd.wave": ATTACHMENT_TYPE.AUDIO,
    "audio/mpeg": ATTACHMENT_TYPE.AUDIO,
    "text/plain": ATTACHMENT_TYPE.TEXT,
    "text/markdown": ATTACHMENT_TYPE.TEXT,
    "application/json": ATTACHMENT_TYPE.TEXT,
    "text/csv": ATTACHMENT_TYPE.CSV,
    "application/csv": ATTACHMENT_TYPE.CSV,
    "application/octet-stream": ATTACHMENT_TYPE.OTHER,
  };

export const ATTACHMENT_ORDER_MAP: Record<ATTACHMENT_TYPE, number> = {
  [ATTACHMENT_TYPE.PDF]: 0,
  [ATTACHMENT_TYPE.TEXT]: 1,
  [ATTACHMENT_TYPE.CSV]: 2,
  [ATTACHMENT_TYPE.OTHER]: 3,
  [ATTACHMENT_TYPE.AUDIO]: 4,
  [ATTACHMENT_TYPE.VIDEO]: 5,
  [ATTACHMENT_TYPE.IMAGE]: 6,
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
  [ATTACHMENT_TYPE.CSV]: FileSpreadsheet,
  [ATTACHMENT_TYPE.OTHER]: File,
  [ATTACHMENT_TYPE.AUDIO]: FileAudio,
  [ATTACHMENT_TYPE.VIDEO]: FileVideo,
  [ATTACHMENT_TYPE.IMAGE]: FileImage,
};
