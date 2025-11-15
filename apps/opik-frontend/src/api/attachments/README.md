# Attachment Upload System

This module provides multipart file upload capabilities for the Opik frontend, supporting both AWS S3 (with presigned URLs) and MinIO (direct backend upload).

## Features

- ✅ Multipart upload for large files (up to ~48.8TB theoretical limit)
- ✅ Automatic detection of S3 vs MinIO upload mode
- ✅ Progress tracking with percentage and bytes
- ✅ Automatic retry logic with exponential backoff
- ✅ File chunking following AWS S3 standards (5MB minimum per part)
- ✅ React hooks for easy integration
- ✅ UI components for upload progress display

## Architecture

The system automatically detects whether to use:
- **S3 mode**: Files are split into chunks and uploaded directly to S3 using presigned URLs
- **MinIO mode**: Files are uploaded directly to the backend

The backend returns `uploadId: "BEMinIO"` for MinIO mode, and a real S3 upload ID for S3 mode.

## Usage

### Basic Upload with Hook

```tsx
import { useAttachmentUpload } from "@/hooks/useAttachmentUpload";

function MyComponent() {
  const { fileInputRef, handleFileSelect, isUploading, progress } =
    useAttachmentUpload({
      entityType: "trace",
      entityId: "trace-123",
      projectName: "my-project",
      maxSizeMB: 2000, // 2GB max
      onUploadSuccess: (fileName) => {
        console.log("Uploaded:", fileName);
      },
    });

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={handleFileSelect}
      />
      <button onClick={() => fileInputRef.current?.click()}>
        Upload File
      </button>
      {isUploading && progress && (
        <div>Progress: {progress.percentage}%</div>
      )}
    </>
  );
}
```

### Using the Upload Field Component

```tsx
import { AttachmentUploadField } from "@/components/shared/AttachmentUpload/AttachmentUploadField";

function MyComponent() {
  return (
    <AttachmentUploadField
      entityType="span"
      entityId="span-456"
      projectName="my-project"
      maxSizeMB={1000}
      onUploadSuccess={(fileName) => {
        console.log("File uploaded:", fileName);
        // Refresh attachments list, etc.
      }}
    />
  );
}
```

### Progress Display

```tsx
import { UploadProgress } from "@/components/shared/UploadProgress/UploadProgress";

function MyComponent() {
  return (
    <UploadProgress
      fileName="large-file.mp4"
      percentage={45}
      isComplete={false}
    />
  );
}
```

### Direct API Usage

```tsx
import { attachmentUploadClient } from "@/api/attachments/attachmentUploadClient";

async function uploadFile(file: File) {
  try {
    await attachmentUploadClient.uploadFile({
      file,
      entityType: "trace",
      entityId: "trace-123",
      projectName: "my-project",
      onProgress: (progress) => {
        console.log(`Progress: ${progress.percentage}%`);
        console.log(`Uploaded: ${progress.loaded} / ${progress.total} bytes`);
      },
    });
    console.log("Upload complete!");
  } catch (error) {
    console.error("Upload failed:", error);
  }
}
```

## File Size Limits

Following AWS S3 standards:

- **Minimum part size**: 5MB
- **Maximum part size**: 5GB
- **Maximum parts**: 10,000
- **Theoretical max file size**: ~48.8TB (10,000 × 5GB)

In practice, frontend size limits are configurable (default 2GB for videos, 200MB for images).

## How It Works

### S3 Upload Flow

1. Frontend calculates number of parts needed
2. Calls `/v1/private/attachment/upload-start` with part count
3. Backend returns presigned URLs for each part
4. Frontend uploads each part directly to S3
5. Frontend calls `/v1/private/attachment/upload-complete` with ETags

### MinIO Upload Flow

1. Frontend calls `/v1/private/attachment/upload-start`
2. Backend returns single URL with `uploadId: "BEMinIO"`
3. Frontend uploads entire file to backend URL
4. No completion call needed (backend handles it)

## Error Handling

The system includes automatic retry logic:

- **Chunk upload failures**: Retries up to 3 times with exponential backoff
- **Network errors**: Automatically retried by axios
- **Validation errors**: Shown to user via toast notifications

## Types

```typescript
interface FileUploadOptions {
  file: File;
  entityType: "trace" | "span";
  entityId: string;
  projectName?: string;
  onProgress?: (progress: UploadProgress) => void;
}

interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}
```

## Testing

To test with different backends:

### S3 Configuration
```bash
export IS_MINIO=false
export S3_BUCKET=your-bucket
export S3_REGION=us-east-1
```

### MinIO Configuration
```bash
export IS_MINIO=true
export S3_URL=http://localhost:9001
export S3_BUCKET=public
```

## Files

- `attachmentUploadClient.ts` - Main upload client
- `types.ts` - TypeScript type definitions
- `/hooks/useAttachmentUpload.ts` - React hook for uploads
- `/hooks/useFileUploadWithProgress.ts` - Low-level progress tracking hook
- `/lib/fileChunking.ts` - File chunking utilities
- `/components/shared/UploadProgress/` - Progress UI component
- `/components/shared/AttachmentUpload/` - Upload field component

