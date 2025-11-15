import { useRef, useState } from "react";
import { useToast } from "@/components/ui/use-toast";
import { attachmentUploadClient } from "@/api/attachments/attachmentUploadClient";
import { UploadProgress } from "@/api/attachments/types";

interface UseAttachmentUploadProps {
  entityType: "trace" | "span";
  entityId: string;
  projectName?: string;
  currentItemsCount?: number;
  maxItems?: number;
  maxSizeMB?: number;
  onUploadSuccess?: (fileName: string) => void;
  onUploadError?: (error: Error) => void;
}

export interface AttachmentUploadState {
  isUploading: boolean;
  progress: UploadProgress | null;
  fileName: string | null;
}

/**
 * Hook for uploading attachments using multipart upload
 * Supports both S3 (presigned URLs) and MinIO (backend upload)
 */
export const useAttachmentUpload = ({
  entityType,
  entityId,
  projectName,
  currentItemsCount = 0,
  maxItems = Infinity,
  maxSizeMB = 2000, // Default 2GB max
  onUploadSuccess,
  onUploadError,
}: UseAttachmentUploadProps) => {
  const { toast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadState, setUploadState] = useState<AttachmentUploadState>({
    isUploading: false,
    progress: null,
    fileName: null,
  });

  const handleFileSelect = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const files = event.target.files;
    if (!files || files.length === 0) return;

    const fileList = Array.from(files);

    // Validate file count
    if (currentItemsCount + fileList.length > maxItems) {
      toast({
        title: "Maximum limit reached",
        description: `Cannot upload ${fileList.length} file(s). Maximum ${maxItems} items allowed.`,
        variant: "destructive",
      });
      return;
    }

    // Validate file size
    const oversizedFiles = fileList.filter(
      (file) => file.size > maxSizeMB * 1024 * 1024,
    );
    if (oversizedFiles.length > 0) {
      toast({
        title: "File too large",
        description: `File "${oversizedFiles[0].name}" exceeds maximum size of ${maxSizeMB}MB.`,
        variant: "destructive",
      });
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      return;
    }

    // Upload files
    for (const file of fileList) {
      await uploadFile(file);
    }

    // Clear input
    if (fileInputRef.current) {
      fileInputRef.current.value = "";
    }
  };

  const uploadFile = async (file: File) => {
    setUploadState({
      isUploading: true,
      progress: { loaded: 0, total: file.size, percentage: 0 },
      fileName: file.name,
    });

    try {
      await attachmentUploadClient.uploadFile({
        file,
        entityType,
        entityId,
        projectName,
        onProgress: (progress) => {
          setUploadState((prev) => ({
            ...prev,
            progress,
          }));
        },
      });

      setUploadState({
        isUploading: false,
        progress: { loaded: file.size, total: file.size, percentage: 100 },
        fileName: file.name,
      });

      toast({
        title: "Upload successful",
        description: `${file.name} has been uploaded successfully`,
      });

      onUploadSuccess?.(file.name);

      // Reset state after a short delay
      setTimeout(() => {
        setUploadState({
          isUploading: false,
          progress: null,
          fileName: null,
        });
      }, 2000);
    } catch (error) {
      const uploadError = error as Error;
      setUploadState({
        isUploading: false,
        progress: null,
        fileName: null,
      });

      toast({
        title: "Upload failed",
        description:
          uploadError.message || "Failed to upload file. Please try again.",
        variant: "destructive",
      });

      onUploadError?.(uploadError);
    }
  };

  const resetState = () => {
    setUploadState({
      isUploading: false,
      progress: null,
      fileName: null,
    });
  };

  return {
    fileInputRef,
    handleFileSelect,
    uploadFile,
    resetState,
    ...uploadState,
  };
};
