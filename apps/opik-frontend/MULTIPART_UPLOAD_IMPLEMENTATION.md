# Multipart Upload Implementation for Frontend

This document describes the implementation of multipart file upload support in the Opik frontend, enabling large file uploads to both AWS S3 and MinIO.

## Overview

The implementation provides a complete multipart upload system that:
- ✅ Supports both S3 (with presigned URLs) and MinIO (direct backend upload)
- ✅ Automatically detects which mode to use based on backend response
- ✅ Handles files up to ~48.8TB theoretical limit (following AWS S3 standards)
- ✅ Provides progress tracking with percentage and bytes uploaded
- ✅ Includes automatic retry logic with exponential backoff
- ✅ Offers React hooks and UI components for easy integration

## Architecture

### Backend Detection

The system automatically detects S3 vs MinIO mode:
- Backend returns `uploadId: "BEMinIO"` for MinIO mode
- Backend returns a real S3 upload ID for S3 mode

### Upload Flow

**S3 Mode:**
1. Calculate number of parts needed (5MB minimum per part)
2. Call `/v1/private/attachment/upload-start` to get presigned URLs
3. Upload each part directly to S3 in parallel
4. Call `/v1/private/attachment/upload-complete` with ETags

**MinIO Mode:**
1. Call `/v1/private/attachment/upload-start`
2. Upload entire file to backend URL
3. Backend handles storage (no completion call needed)

## Files Created

### Core Implementation
- `src/api/attachments/types.ts` - TypeScript type definitions
- `src/api/attachments/attachmentUploadClient.ts` - Main upload client
- `src/lib/fileChunking.ts` - File chunking utilities following AWS S3 standards

### React Hooks
- `src/hooks/useAttachmentUpload.ts` - High-level hook for attachment uploads
- `src/hooks/useFileUploadWithProgress.ts` - Low-level progress tracking hook

### UI Components
- `src/components/ui/progress.tsx` - Progress bar component (Radix UI based)
- `src/components/shared/UploadProgress/UploadProgress.tsx` - Upload progress display
- `src/components/shared/AttachmentUpload/AttachmentUploadField.tsx` - Complete upload field component

### Documentation
- `src/api/attachments/README.md` - Comprehensive usage guide and API documentation

## Dependencies Added

- `@radix-ui/react-progress` - Progress bar UI component

## Key Features

### File Chunking
- Minimum part size: 5MB (AWS S3 standard)
- Maximum part size: 5GB
- Maximum parts: 10,000
- Automatic calculation of optimal part size based on file size

### Error Handling
- Automatic retry logic (up to 3 attempts)
- Exponential backoff between retries
- User-friendly error messages via toast notifications

### Progress Tracking
- Real-time upload progress (bytes and percentage)
- Per-chunk progress for large files
- Visual progress indicators

## Usage Examples

### Using the Hook

```tsx
import { useAttachmentUpload } from "@/hooks/useAttachmentUpload";

function MyComponent() {
  const { fileInputRef, handleFileSelect, isUploading, progress } =
    useAttachmentUpload({
      entityType: "trace",
      entityId: "trace-123",
      projectName: "my-project",
      maxSizeMB: 2000,
      onUploadSuccess: (fileName) => {
        console.log("Uploaded:", fileName);
      },
    });

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        onChange={handleFileSelect}
      />
      {isUploading && progress && (
        <div>Progress: {progress.percentage}%</div>
      )}
    </>
  );
}
```

### Using the Component

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
      }}
    />
  );
}
```

## Testing

### Running Type Checks
```bash
cd apps/opik-frontend
npm run typecheck
```

### Running Linter
```bash
npm run lint
```

### Testing with S3
Set environment variables in the backend:
```bash
export IS_MINIO=false
export S3_BUCKET=your-bucket
export S3_REGION=us-east-1
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
```

### Testing with MinIO
Set environment variables in the backend:
```bash
export IS_MINIO=true
export S3_URL=http://localhost:9001
export S3_BUCKET=public
```

## Integration Points

The implementation is designed to be easily integrated into existing Opik UI components:

1. **Trace/Span Detail Views** - Can add file upload fields for attaching files to traces or spans
2. **Datasets** - Can extend for uploading large dataset files
3. **Experiments** - Can upload experiment artifacts and results

## Future Enhancements

Potential improvements for future iterations:

1. **Parallel Chunk Uploads** - Upload multiple chunks simultaneously for faster speeds
2. **Resume Support** - Allow resuming interrupted uploads
3. **Drag & Drop** - Enhanced drag-and-drop file upload interface
4. **Multiple File Upload** - Support uploading multiple files at once
5. **File Preview** - Preview files before uploading
6. **Upload Queue** - Queue multiple file uploads

## Compliance

- Follows AWS S3 multipart upload standards
- Compatible with backend API endpoints
- Adheres to Opik frontend code style and linting rules
- TypeScript type-safe implementation

## Performance Considerations

- Chunks are uploaded sequentially (can be parallelized in future)
- Progress is updated per chunk for responsive UI
- Memory-efficient chunk reading using Blob slicing
- Automatic cleanup of completed/failed uploads

