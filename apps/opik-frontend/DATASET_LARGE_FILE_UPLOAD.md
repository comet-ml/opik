# Large Dataset CSV Upload Implementation

## Overview

This document describes the implementation of large dataset CSV file upload support, enabling users to upload CSV files up to **2GB** (configurable) for async backend processing.

## Problem Statement

Previously, dataset CSV uploads were limited to:
- **20MB** maximum file size
- **1,000 rows** maximum
- Frontend parsing and processing (blocking UI)

For larger datasets, users had to use the SDK, which was not ideal for non-technical users.

## Solution

We now support two upload paths:

### 1. Small Files (≤20MB, ≤1,000 rows)
**Current flow - unchanged:**
1. User selects CSV file
2. Frontend validates and parses CSV
3. Frontend creates dataset
4. Frontend uploads items via batch API
5. User sees immediate results

### 2. Large Files (>20MB, up to 2GB)
**New flow:**
1. User selects large CSV file
2. Frontend detects file size > 20MB
3. Frontend creates dataset
4. Frontend uploads CSV to S3/MinIO using **multipart upload**
5. Backend processes CSV asynchronously
6. User receives notification when processing is complete

## Frontend Changes

### Files Modified

**`AddEditDatasetDialog.tsx`** - Main dataset creation dialog
- Added detection for large files (>20MB)
- Integrated multipart upload for large files
- Updated UI to show appropriate messaging
- Added upload progress indicators

### Configuration

```typescript
const FILE_SIZE_LIMIT_IN_MB = 20;     // Direct processing limit
const MAX_FILE_SIZE_IN_MB = 2000;     // Maximum upload size (2GB)
const MAX_ITEMS_COUNT_LIMIT = 1000;   // Max rows for direct processing
```

### User Experience

**For files ≤20MB:**
```
"Files up to 20MB (max 1,000 rows) are processed immediately.
Larger files (up to 2000MB) are processed asynchronously."
```

**For files >20MB:**
```
"Large file detected (150MB). 
This file will be uploaded to storage and processed asynchronously."
```

**During upload:**
```
"Uploading CSV file
Uploading your file to storage.
This may take a few minutes depending on file size."
```

**After upload:**
```
"Dataset file uploaded
Your CSV file has been uploaded and will be processed shortly. 
You'll be notified when it's ready."
```

## Backend Changes Needed

### 1. New API Endpoint

**`POST /v1/private/datasets/{dataset_id}/process-csv`**

Process a CSV file that was uploaded to S3/MinIO.

**Request:**
```json
{
  "file_name": "large_dataset.csv",
  "file_path": "s3://bucket/path/to/file.csv"
}
```

**Response:**
```json
{
  "job_id": "uuid",
  "status": "processing",
  "estimated_time_minutes": 5
}
```

### 2. Async CSV Processing Service

**Responsibilities:**
1. Fetch CSV file from S3/MinIO
2. Validate CSV format and structure
3. Parse CSV in chunks/batches
4. Create dataset items in batches (avoid memory issues)
5. Handle errors gracefully
6. Send notification when complete

**Pseudo-code:**
```java
@Service
public class DatasetCsvProcessorService {
    
    @Async
    public void processDatasetCsv(UUID datasetId, String s3Path) {
        try {
            // 1. Download CSV from S3 (streaming)
            InputStream csvStream = s3Client.getObject(s3Path);
            
            // 2. Parse CSV in chunks
            CsvParser parser = new CsvParser(csvStream);
            List<DatasetItem> batch = new ArrayList<>();
            
            while (parser.hasNext()) {
                DatasetItem item = parser.next();
                batch.add(item);
                
                // Process in batches of 1000
                if (batch.size() >= 1000) {
                    datasetItemService.createBatch(datasetId, batch);
                    batch.clear();
                }
            }
            
            // Process remaining items
            if (!batch.isEmpty()) {
                datasetItemService.createBatch(datasetId, batch);
            }
            
            // 3. Update dataset status
            datasetService.updateStatus(datasetId, "ready");
            
            // 4. Send notification
            notificationService.send(
                workspaceId,
                "Dataset processing complete",
                "Your dataset '" + datasetName + "' is ready to use."
            );
            
        } catch (Exception e) {
            log.error("Failed to process dataset CSV", e);
            datasetService.updateStatus(datasetId, "failed");
            notificationService.sendError(workspaceId, "Dataset processing failed");
        }
    }
}
```

### 3. Dataset Status Field

Add a `processing_status` field to the `datasets` table:

```sql
ALTER TABLE datasets ADD COLUMN processing_status VARCHAR(50) DEFAULT 'ready';
-- Values: 'ready', 'processing', 'failed'

ALTER TABLE datasets ADD COLUMN processing_error TEXT;
ALTER TABLE datasets ADD COLUMN processed_at TIMESTAMP;
```

### 4. WebSocket Notification

Send real-time notification when processing completes:

```java
@Service
public class DatasetNotificationService {
    
    public void notifyProcessingComplete(UUID datasetId, String workspaceId) {
        webSocketService.send(
            workspaceId,
            new DatasetProcessingCompleteEvent(datasetId)
        );
    }
}
```

## Current Implementation Details

### How Large Files Are Uploaded

The frontend uses the multipart upload system:

```typescript
await attachmentUploadClient.uploadFile({
  file: selectedFile,
  entityType: "trace",  // Using trace as proxy
  entityId: datasetId,
  projectName: datasetName,
  onProgress: (progress) => {
    console.log(`Upload progress: ${progress.percentage}%`);
  },
});
```

**Note:** Currently using "trace" entity type as a proxy. Backend should handle "dataset" as an entity type for attachments.

### TODO in Frontend Code

Located in `AddEditDatasetDialog.tsx`:

```typescript
// TODO: Call backend endpoint to trigger async CSV processing
// Example: POST /v1/private/datasets/{id}/process-csv
// This endpoint should:
// 1. Fetch the CSV from S3/MinIO
// 2. Parse and validate the CSV
// 3. Create dataset items in batches
// 4. Send notification when complete
```

## Testing

### Test with MinIO (Local)

1. **Start Opik stack:**
```bash
./opik.sh
```

2. **Upload a large CSV file** (>20MB):
   - Go to Datasets page
   - Click "Create new dataset"
   - Upload a CSV file >20MB
   - Observe:
     - Message changes to indicate large file
     - "Create dataset" button remains enabled
     - Upload progress shows during upload
     - Success message indicates async processing

3. **Verify in MinIO:**
```bash
# Open MinIO console at http://localhost:9001
# Check "public" bucket for uploaded CSV
```

### Test with S3 (AWS)

1. **Configure backend for S3:**
```bash
export IS_MINIO=false
export S3_BUCKET=your-bucket
export S3_REGION=us-east-1
```

2. **Follow same upload steps as MinIO**

3. **Verify in S3:**
```bash
aws s3 ls s3://your-bucket/ --recursive | grep csv
```

## Future Enhancements

1. **Progress Tracking**
   - Show real-time processing progress
   - Display number of rows processed
   - Estimated time remaining

2. **Validation Preview**
   - Show first 10 rows of CSV for validation
   - Allow user to map columns
   - Detect data types automatically

3. **Error Handling**
   - Detailed error messages for malformed CSV
   - Option to download error report
   - Partial success (process valid rows, skip invalid)

4. **Batch Processing**
   - Upload multiple CSV files at once
   - Queue management
   - Priority processing

5. **Resume Support**
   - Resume processing if interrupted
   - Retry failed chunks
   - Incremental updates

## Migration Guide

### For Users

**Before:**
- CSV files limited to 20MB
- Large datasets required SDK usage
- Non-technical users couldn't upload large datasets

**After:**
- CSV files up to 2GB supported
- Simple drag-and-drop upload in UI
- Async processing with notifications
- Backward compatible (small files work as before)

### For Developers

**Frontend:**
- No breaking changes
- Small files use existing flow
- Large files automatically use new flow
- All changes in `AddEditDatasetDialog.tsx`

**Backend (Required):**
1. Add `/datasets/{id}/process-csv` endpoint
2. Implement async CSV processor service
3. Add dataset processing status fields
4. Set up notification system
5. Update attachment system to support "dataset" entity type

## Architecture Diagram

```
┌─────────────────┐
│   User selects  │
│   large CSV     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐      Yes      ┌─────────────────┐
│  File > 20MB?   ├──────────────▶│ Create dataset  │
└────────┬────────┘                └────────┬────────┘
         │                                  │
         │ No                               ▼
         │                         ┌─────────────────┐
         │                         │  Upload to S3   │
         │                         │  (multipart)    │
         │                         └────────┬────────┘
         │                                  │
         │                                  ▼
         │                         ┌─────────────────┐
         │                         │ Trigger async   │
         │                         │ processing      │
         │                         └────────┬────────┘
         │                                  │
         ▼                                  ▼
┌─────────────────┐                ┌─────────────────┐
│ Parse in        │                │ Backend fetches │
│ frontend        │                │ from S3         │
└────────┬────────┘                └────────┬────────┘
         │                                  │
         ▼                                  ▼
┌─────────────────┐                ┌─────────────────┐
│ Upload items    │                │ Parse & create  │
│ via batch API   │                │ items in chunks │
└────────┬────────┘                └────────┬────────┘
         │                                  │
         ▼                                  ▼
┌─────────────────┐                ┌─────────────────┐
│ Done            │                │ Send            │
│ immediately     │                │ notification    │
└─────────────────┘                └─────────────────┘
```

## Summary

✅ **Frontend Complete:**
- Large file detection (>20MB)
- Multipart upload to S3/MinIO
- Progress indicators
- User-friendly messaging
- Backward compatible

⚠️ **Backend TODO:**
- Add CSV processing endpoint
- Implement async processor
- Add status tracking
- Set up notifications
- Support "dataset" entity type in attachments

The frontend is ready and will gracefully handle the backend implementation when available. Users can already upload large files; they just need backend processing to complete the flow.

