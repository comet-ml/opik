import { useState, useCallback } from "react";
import { attachmentUploadClient } from "@/api/attachments/attachmentUploadClient";
import { FileUploadOptions, UploadProgress } from "@/api/attachments/types";
import { useToast } from "@/components/ui/use-toast";

interface UseFileUploadWithProgressOptions {
  onSuccess?: (fileName: string) => void;
  onError?: (error: Error) => void;
}

export interface FileUploadState {
  isUploading: boolean;
  progress: UploadProgress | null;
  error: Error | null;
  uploadedFileName: string | null;
}

/**
 * Hook for uploading files with progress tracking
 * Supports both S3 (with presigned URLs) and MinIO (direct backend upload)
 */
export const useFileUploadWithProgress = (
  options?: UseFileUploadWithProgressOptions,
) => {
  const { toast } = useToast();
  const [uploadState, setUploadState] = useState<FileUploadState>({
    isUploading: false,
    progress: null,
    error: null,
    uploadedFileName: null,
  });

  const uploadFile = useCallback(
    async (uploadOptions: FileUploadOptions) => {
      setUploadState({
        isUploading: true,
        progress: { loaded: 0, total: uploadOptions.file.size, percentage: 0 },
        error: null,
        uploadedFileName: null,
      });

      try {
        await attachmentUploadClient.uploadFile({
          ...uploadOptions,
          onProgress: (progress) => {
            setUploadState((prev) => ({
              ...prev,
              progress,
            }));
            uploadOptions.onProgress?.(progress);
          },
        });

        setUploadState({
          isUploading: false,
          progress: {
            loaded: uploadOptions.file.size,
            total: uploadOptions.file.size,
            percentage: 100,
          },
          error: null,
          uploadedFileName: uploadOptions.file.name,
        });

        options?.onSuccess?.(uploadOptions.file.name);
      } catch (error) {
        const uploadError = error as Error;
        setUploadState({
          isUploading: false,
          progress: null,
          error: uploadError,
          uploadedFileName: null,
        });

        toast({
          title: "Upload failed",
          description:
            uploadError.message || "Failed to upload file. Please try again.",
          variant: "destructive",
        });

        options?.onError?.(uploadError);
      }
    },
    [options, toast],
  );

  const resetUploadState = useCallback(() => {
    setUploadState({
      isUploading: false,
      progress: null,
      error: null,
      uploadedFileName: null,
    });
  }, []);

  return {
    uploadFile,
    resetUploadState,
    ...uploadState,
  };
};
