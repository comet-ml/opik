import axios from "axios";
import { ATTACHMENTS_REST_ENDPOINT, BASE_API_URL } from "@/api/api";
import { calculatePartsNumber, splitFileIntoChunks } from "@/lib/fileChunking";
import {
  CompleteMultipartUploadRequest,
  FileUploadOptions,
  MultipartUploadPart,
  StartMultipartUploadRequest,
  StartMultipartUploadResponse,
} from "./types";

/**
 * Get the absolute backend URL for constructing upload URLs
 * In development, this resolves relative BASE_API_URL to absolute URL
 * In production, BASE_API_URL should already be absolute or properly configured
 */
function getBackendBaseUrl(): string {
  // If BASE_API_URL is absolute, use it directly
  if (
    BASE_API_URL.startsWith("http://") ||
    BASE_API_URL.startsWith("https://")
  ) {
    return BASE_API_URL;
  }

  // Otherwise, construct absolute URL using window.location
  // This handles relative URLs like "/api"
  const protocol = window.location.protocol;
  const hostname = window.location.hostname;

  // In development, the backend typically runs on a different port
  // Check if there's a proxy configuration or use the current origin
  // For Vite dev server with proxy, /api routes to backend automatically
  return `${protocol}//${hostname}${
    window.location.port ? `:${window.location.port}` : ""
  }`;
}

/**
 * Client for uploading files to S3 or MinIO via multipart upload
 * Both S3 and MinIO use the same multipart upload flow with presigned URLs
 */
export class AttachmentUploadClient {
  private axiosInstance = axios.create({
    baseURL: BASE_API_URL,
    withCredentials: true,
  });

  /**
   * Starts a multipart upload
   */
  private async startMultipartUpload(
    request: StartMultipartUploadRequest,
  ): Promise<StartMultipartUploadResponse> {
    const response =
      await this.axiosInstance.post<StartMultipartUploadResponse>(
        `${ATTACHMENTS_REST_ENDPOINT}upload-start`,
        request,
      );
    return response.data;
  }

  /**
   * Completes a multipart upload (for S3 only)
   */
  private async completeMultipartUpload(
    request: CompleteMultipartUploadRequest,
  ): Promise<void> {
    await this.axiosInstance.post(
      `${ATTACHMENTS_REST_ENDPOINT}upload-complete`,
      request,
    );
  }

  /**
   * Uploads a single chunk directly to S3 using a presigned URL
   */
  private async uploadChunkToS3(
    url: string,
    chunk: Blob,
    onProgress?: (loaded: number) => void,
  ): Promise<string> {
    const response = await axios.put(url, chunk, {
      headers: {
        "Content-Type": "application/octet-stream",
      },
      onUploadProgress: (progressEvent) => {
        if (onProgress && progressEvent.loaded) {
          onProgress(progressEvent.loaded);
        }
      },
    });

    // Extract ETag from response headers
    const etag = response.headers["etag"];
    if (!etag) {
      throw new Error("No ETag returned from S3");
    }

    // Remove quotes from ETag if present
    return etag.replace(/"/g, "");
  }

  /**
   * Uploads a file using multipart upload (supports both S3 and MinIO)
   * Both S3 and MinIO use presigned URLs for direct uploads
   */
  async uploadFile(options: FileUploadOptions): Promise<void> {
    const { file, entityType, entityId, projectName } = options;

    // Calculate number of parts needed
    const partsNumber = calculatePartsNumber(file.size);

    // Encode the backend base URL path (not used for S3/MinIO multipart, but kept for compatibility)
    const basePath = btoa(getBackendBaseUrl());

    // Start multipart upload
    const startRequest: StartMultipartUploadRequest = {
      file_name: file.name,
      num_of_file_parts: partsNumber,
      entity_type: entityType,
      entity_id: entityId,
      path: basePath,
      mime_type: file.type,
      project_name: projectName,
    };

    const uploadMetadata = await this.startMultipartUpload(startRequest);

    // Use S3 multipart upload for both S3 and MinIO (MinIO is S3-compatible)
    await this.uploadToS3(file, uploadMetadata, options);
  }

  /**
   * Uploads file chunks to S3 using presigned URLs
   */
  private async uploadToS3(
    file: File,
    uploadMetadata: StartMultipartUploadResponse,
    options: FileUploadOptions,
  ): Promise<void> {
    const { entityType, entityId, projectName, onProgress } = options;
    const uploadedParts: MultipartUploadPart[] = [];
    const chunks = Array.from(splitFileIntoChunks(file));

    let uploadedBytes = 0;
    const totalBytes = file.size;

    // Upload each chunk
    for (let i = 0; i < chunks.length; i++) {
      const chunk = chunks[i];
      const presignedUrl = uploadMetadata.pre_sign_urls[i];

      if (!presignedUrl) {
        throw new Error(`No presigned URL for part ${chunk.partNumber}`);
      }

      // Upload chunk with retry logic
      const etag = await this.uploadChunkWithRetry(
        presignedUrl,
        chunk.blob,
        (loaded) => {
          if (onProgress) {
            const currentProgress = uploadedBytes + loaded;
            onProgress({
              loaded: currentProgress,
              total: totalBytes,
              percentage: Math.round((currentProgress / totalBytes) * 100),
            });
          }
        },
      );

      uploadedBytes += chunk.size;
      uploadedParts.push({
        e_tag: etag,
        part_number: chunk.partNumber,
      });

      // Update final progress for this chunk
      if (onProgress) {
        onProgress({
          loaded: uploadedBytes,
          total: totalBytes,
          percentage: Math.round((uploadedBytes / totalBytes) * 100),
        });
      }
    }

    // Complete the multipart upload
    const completeRequest: CompleteMultipartUploadRequest = {
      file_name: file.name,
      entity_type: entityType,
      entity_id: entityId,
      file_size: file.size,
      upload_id: uploadMetadata.upload_id,
      uploaded_file_parts: uploadedParts,
      project_name: projectName,
      mime_type: file.type,
    };

    await this.completeMultipartUpload(completeRequest);
  }

  /**
   * Uploads a chunk with automatic retry on failure
   */
  private async uploadChunkWithRetry(
    url: string,
    chunk: Blob,
    onProgress?: (loaded: number) => void,
    maxRetries: number = 3,
  ): Promise<string> {
    let lastError: Error | null = null;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        return await this.uploadChunkToS3(url, chunk, onProgress);
      } catch (error) {
        lastError = error as Error;
        // Wait before retrying (exponential backoff)
        if (attempt < maxRetries - 1) {
          await new Promise((resolve) =>
            setTimeout(resolve, Math.pow(2, attempt) * 1000),
          );
        }
      }
    }

    throw new Error(
      `Failed to upload chunk after ${maxRetries} attempts: ${lastError?.message}`,
    );
  }
}

// Export a singleton instance
export const attachmentUploadClient = new AttachmentUploadClient();
