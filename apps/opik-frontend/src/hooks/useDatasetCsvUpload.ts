import { useState, useCallback } from "react";
import { attachmentUploadClient } from "@/api/attachments/attachmentUploadClient";
import { UploadProgress } from "@/api/attachments/types";
import { useToast } from "@/components/ui/use-toast";

interface UseDatasetCsvUploadOptions {
  datasetId: string;
  datasetName: string;
  onSuccess?: (fileName: string) => void;
  onError?: (error: Error) => void;
}

export interface DatasetCsvUploadState {
  isUploading: boolean;
  progress: UploadProgress | null;
  error: Error | null;
}

/**
 * Hook for uploading large dataset CSV files to S3/MinIO for async processing
 * This is used when CSV files exceed the 20MB limit for direct frontend processing
 */
export const useDatasetCsvUpload = ({
  datasetId,
  datasetName,
  onSuccess,
  onError,
}: UseDatasetCsvUploadOptions) => {
  const { toast } = useToast();
  const [uploadState, setUploadState] = useState<DatasetCsvUploadState>({
    isUploading: false,
    progress: null,
    error: null,
  });

  const uploadCsvFile = useCallback(
    async (file: File) => {
      setUploadState({
        isUploading: true,
        progress: { loaded: 0, total: file.size, percentage: 0 },
        error: null,
      });

      try {
        // Upload CSV file as an attachment to the dataset
        // Use "dataset" as entity type and dataset ID as entity ID
        await attachmentUploadClient.uploadFile({
          file,
          entityType: "trace", // Using trace type as proxy for now
          entityId: datasetId,
          projectName: datasetName,
          onProgress: (progress) => {
            setUploadState((prev) => ({
              ...prev,
              progress,
            }));
          },
        });

        setUploadState({
          isUploading: false,
          progress: {
            loaded: file.size,
            total: file.size,
            percentage: 100,
          },
          error: null,
        });

        toast({
          title: "CSV uploaded successfully",
          description:
            "Your dataset file has been uploaded and is being processed. This may take a few minutes.",
        });

        onSuccess?.(file.name);
      } catch (error) {
        const uploadError = error as Error;
        setUploadState({
          isUploading: false,
          progress: null,
          error: uploadError,
        });

        toast({
          title: "Upload failed",
          description:
            uploadError.message ||
            "Failed to upload CSV file. Please try again.",
          variant: "destructive",
        });

        onError?.(uploadError);
      }
    },
    [datasetId, datasetName, onSuccess, onError, toast],
  );

  const resetState = useCallback(() => {
    setUploadState({
      isUploading: false,
      progress: null,
      error: null,
    });
  }, []);

  return {
    uploadCsvFile,
    resetState,
    ...uploadState,
  };
};
