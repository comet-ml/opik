import React, { useState } from "react";
import {
  ATTACHMENT_TYPE,
  AttachmentPreviewData,
  ParsedMediaData,
} from "@/types/attachments";
import AttachmentThumbnail from "@/components/shared/attachments/AttachmentThumbnail/AttachmentThumbnail";
import AttachmentPreviewDialog from "@/components/shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";

type ImagesListWrapperProps = {
  media: ParsedMediaData[];
};

const ImagesListWrapper: React.FC<ImagesListWrapperProps> = ({ media }) => {
  const [previewData, setPreviewData] = useState<AttachmentPreviewData | null>(
    null,
  );

  // No deduplication - display all media items as passed by parent
  // This allows LLM message components to show multiple placeholders
  // that resolve to the same URL (e.g., [image_0] and [image_1])
  // Deduplication should be done at the data source level if needed
  return (
    <div className="flex flex-wrap gap-2">
      {media.map((data, index) => (
        <AttachmentThumbnail
          key={`${data.url}-${index}`}
          previewData={data}
          onExpand={setPreviewData}
        />
      ))}

      <AttachmentPreviewDialog
        open={Boolean(previewData)}
        setOpen={() => setPreviewData(null)}
        type={previewData?.type ?? ATTACHMENT_TYPE.IMAGE}
        name={previewData?.name ?? ""}
        url={previewData?.url ?? ""}
      ></AttachmentPreviewDialog>
    </div>
  );
};

export default ImagesListWrapper;
