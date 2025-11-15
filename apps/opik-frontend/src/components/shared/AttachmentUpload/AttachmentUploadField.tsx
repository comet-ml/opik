import React from "react";
import { Upload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAttachmentUpload } from "@/hooks/useAttachmentUpload";
import { UploadProgress } from "@/components/shared/UploadProgress/UploadProgress";

interface AttachmentUploadFieldProps {
  entityType: "trace" | "span";
  entityId: string;
  projectName?: string;
  accept?: string;
  maxSizeMB?: number;
  onUploadSuccess?: (fileName: string) => void;
  disabled?: boolean;
}

/**
 * Component for uploading attachments with multipart upload support
 * Supports both S3 (with presigned URLs) and MinIO (direct backend upload)
 *
 * @example
 * ```tsx
 * <AttachmentUploadField
 *   entityType="trace"
 *   entityId={traceId}
 *   projectName="my-project"
 *   maxSizeMB={2000}
 *   onUploadSuccess={(fileName) => console.log('Uploaded:', fileName)}
 * />
 * ```
 */
export const AttachmentUploadField: React.FC<AttachmentUploadFieldProps> = ({
  entityType,
  entityId,
  projectName,
  accept,
  maxSizeMB = 2000, // Default 2GB
  onUploadSuccess,
  disabled = false,
}) => {
  const { fileInputRef, handleFileSelect, isUploading, progress, fileName } =
    useAttachmentUpload({
      entityType,
      entityId,
      projectName,
      maxSizeMB,
      onUploadSuccess,
    });

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <input
          ref={fileInputRef}
          type="file"
          accept={accept}
          className="hidden"
          disabled={disabled || isUploading}
          onChange={handleFileSelect}
        />
        <Button
          type="button"
          variant="outline"
          size="default"
          disabled={disabled || isUploading}
          onClick={() => fileInputRef.current?.click()}
        >
          <Upload className="mr-2 size-4" />
          {isUploading ? "Uploading..." : "Upload File"}
        </Button>
        <span className="comet-body-s text-muted-foreground">
          Max size: {maxSizeMB}MB
        </span>
      </div>

      {isUploading && fileName && progress && (
        <UploadProgress
          fileName={fileName}
          percentage={progress.percentage}
          isComplete={false}
        />
      )}
    </div>
  );
};
