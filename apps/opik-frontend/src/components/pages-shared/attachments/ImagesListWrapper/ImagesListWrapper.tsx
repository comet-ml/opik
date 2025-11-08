import React, { useMemo, useState } from "react";
import {
  ATTACHMENT_TYPE,
  AttachmentPreviewData,
  ParsedImageData,
} from "@/types/attachments";
import AttachmentThumbnail from "@/components/pages-shared/attachments/AttachmentThumbnail/AttachmentThumbnail";
import AttachmentPreviewDialog from "@/components/pages-shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";

type ImagesListWrapperProps = {
  images: ParsedImageData[];
};

const ImagesListWrapper: React.FC<ImagesListWrapperProps> = ({ images }) => {
  const [previewData, setPreviewData] = useState<AttachmentPreviewData | null>(
    null,
  );

  const previewDataArray = useMemo(() => {
    return images.map((image) => ({
      name: image.name,
      url: image.url,
      type: ATTACHMENT_TYPE.IMAGE,
    }));
  }, [images]);

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
