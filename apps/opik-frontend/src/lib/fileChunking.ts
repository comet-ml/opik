/**
 * File chunking utilities for multipart uploads
 * Based on AWS S3 limits: https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html
 */

export const MIN_FILE_PART_SIZE = 5 * 1024 * 1024; // 5MB
export const MAX_FILE_PART_SIZE = 5 * 1024 * 1024 * 1024; // 5GB
export const MAX_SUPPORTED_PARTS_NUMBER = 10000;

export interface FileChunk {
  blob: Blob;
  partNumber: number;
  size: number;
}

export class FileChunkingError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FileChunkingError";
  }
}

/**
 * Calculates the number of parts needed for a file upload
 * @param fileSize - Size of the file in bytes
 * @param maxPartSize - Maximum size per part (default: MIN_FILE_PART_SIZE)
 * @returns Number of parts needed
 */
export function calculatePartsNumber(
  fileSize: number,
  maxPartSize: number = MIN_FILE_PART_SIZE,
): number {
  if (fileSize === 0) {
    throw new FileChunkingError("Cannot upload empty file");
  }

  // Normalize part size to be within bounds
  const normalizedPartSize = Math.max(
    MIN_FILE_PART_SIZE,
    Math.min(maxPartSize, MAX_FILE_PART_SIZE),
  );

  let partsNumber = Math.ceil(fileSize / normalizedPartSize);

  if (partsNumber > MAX_SUPPORTED_PARTS_NUMBER) {
    partsNumber = MAX_SUPPORTED_PARTS_NUMBER;
  }

  // Check that we're still within part size limits
  const calculatedPartSize = Math.ceil(fileSize / partsNumber);
  if (calculatedPartSize > MAX_FILE_PART_SIZE) {
    throw new FileChunkingError(
      `File is too large to be uploaded. File size: ${fileSize} bytes`,
    );
  }

  return partsNumber;
}

/**
 * Calculates the optimal part size for a file
 * @param fileSize - Size of the file in bytes
 * @returns Optimal part size in bytes
 */
export function calculatePartSize(fileSize: number): number {
  if (fileSize === 0) {
    throw new FileChunkingError("Cannot upload empty file");
  }

  const partsNumber = calculatePartsNumber(fileSize);
  const partSize = Math.ceil(fileSize / partsNumber);

  return Math.max(MIN_FILE_PART_SIZE, partSize);
}

/**
 * Splits a file into chunks for multipart upload
 * @param file - File to split
 * @param partSize - Size of each part (optional, will be calculated if not provided)
 * @returns Array of file chunks
 */
export function* splitFileIntoChunks(
  file: File,
  partSize?: number,
): Generator<FileChunk> {
  const calculatedPartSize = partSize || calculatePartSize(file.size);
  let offset = 0;
  let partNumber = 1;

  while (offset < file.size) {
    const end = Math.min(offset + calculatedPartSize, file.size);
    const blob = file.slice(offset, end);

    yield {
      blob,
      partNumber,
      size: blob.size,
    };

    offset = end;
    partNumber++;
  }
}

/**
 * Reads a blob as ArrayBuffer
 * @param blob - Blob to read
 * @returns Promise that resolves to ArrayBuffer
 */
export function readBlobAsArrayBuffer(blob: Blob): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (reader.result instanceof ArrayBuffer) {
        resolve(reader.result);
      } else {
        reject(new Error("Failed to read blob as ArrayBuffer"));
      }
    };
    reader.onerror = () => {
      reject(new Error("Failed to read blob"));
    };
    reader.readAsArrayBuffer(blob);
  });
}
