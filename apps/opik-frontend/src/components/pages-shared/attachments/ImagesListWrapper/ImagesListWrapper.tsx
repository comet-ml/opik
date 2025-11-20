import React, { useMemo, useState } from "react";
import {
  ATTACHMENT_TYPE,
  AttachmentPreviewData,
  ParsedMediaData,
} from "@/types/attachments";
import AttachmentThumbnail from "@/components/pages-shared/attachments/AttachmentThumbnail/AttachmentThumbnail";
import AttachmentPreviewDialog from "@/components/pages-shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";

type ImagesListWrapperProps = {
  media: ParsedMediaData[];
};

const ImagesListWrapper: React.FC<ImagesListWrapperProps> = ({ media }) => {
  const [previewData, setPreviewData] = useState<AttachmentPreviewData | null>(
    null,
  );

  const previewDataArray = useMemo(() => {
    return media.map((item) => ({
      name: item.name,
      url: item.url,
      type: item.type,
    }));
  }, [media]);

  return (
    <div className="flex flex-wrap gap-2">
      {previewDataArray.map((data) => (
        <AttachmentThumbnail
          key={data.url}
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
