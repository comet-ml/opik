import React, { useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Span, Trace } from "@/types/traces";
import useAttachmentsList from "@/api/attachments/useAttachmentsList";
import { isObjectSpan } from "@/lib/traces";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import AttachmentThumbnail from "@/components/pages-shared/attachments/AttachmentThumbnail/AttachmentThumbnail";
import AttachmentPreviewDialog from "@/components/pages-shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";
import {
  ATTACHMENT_TYPE,
  AttachmentPreviewData,
  AttachmentWithType,
  ParsedImageData,
} from "@/types/attachments";
import {
  ATTACHMENT_ORDER_MAP,
  MINE_TYPE_TO_ATTACHMENT_TYPE_MAP,
} from "@/constants/attachments";

type AttachmentsListProps = {
  data: Trace | Span;
  images: ParsedImageData[];
};

const AttachmentsList: React.FC<AttachmentsListProps> = ({ data, images }) => {
  const isSpan = isObjectSpan(data);
  const [previewData, setPreviewData] = useState<AttachmentPreviewData | null>(
    null,
  );

  const { data: attachmentsData } = useAttachmentsList(
    {
      projectId: data.project_id,
      id: data.id,
      type: isSpan ? "span" : "trace",
      page: 1,
      size: 1000,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const attachments: AttachmentWithType[] = useMemo(
    () =>
      (attachmentsData?.content ?? [])
        .map((attachment) => {
          return {
            ...attachment,
            type:
              MINE_TYPE_TO_ATTACHMENT_TYPE_MAP[attachment.mime_type] ??
              ATTACHMENT_TYPE.OTHER,
          };
        })
        .sort(
          (a1, a2) =>
            ATTACHMENT_ORDER_MAP[a1.type] - ATTACHMENT_ORDER_MAP[a2.type],
        ),
    [attachmentsData?.content],
  );

  const previewDataArray = useMemo(() => {
    return [
      ...attachments.map((attachment) => ({
        name: attachment.file_name,
        url: attachment.link,
        type: attachment.type,
      })),
      ...images.map((image) => ({
        name: image.name,
        url: image.url,
        type: ATTACHMENT_TYPE.IMAGE,
      })),
    ];
  }, [attachments, images]);

  const hasAttachments = Boolean(images.length) || Boolean(attachments.length);
  return hasAttachments ? (
    <AccordionItem value="attachments">
      <AccordionTrigger>Attachments</AccordionTrigger>
      <AccordionContent>
        <div className="flex flex-wrap gap-2">
          {previewDataArray.map((data) => (
            <AttachmentThumbnail
              key={data.url}
              previewData={data}
              onExpand={setPreviewData}
            />
          ))}
        </div>
        <AttachmentPreviewDialog
          open={Boolean(previewData)}
          setOpen={() => setPreviewData(null)}
          type={previewData?.type ?? ATTACHMENT_TYPE.IMAGE}
          name={previewData?.name ?? ""}
          url={previewData?.url ?? ""}
        ></AttachmentPreviewDialog>
      </AccordionContent>
    </AccordionItem>
  ) : null;
};

export default AttachmentsList;
