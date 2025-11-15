export interface StartMultipartUploadRequest {
  file_name: string;
  num_of_file_parts: number;
  entity_type: "trace" | "span";
  entity_id: string;
  path: string;
  mime_type?: string;
  project_name?: string;
}

export interface StartMultipartUploadResponse {
  upload_id: string;
  pre_sign_urls: string[];
}

export interface MultipartUploadPart {
  e_tag: string;
  part_number: number;
}

export interface CompleteMultipartUploadRequest {
  file_name: string;
  entity_type: "trace" | "span";
  entity_id: string;
  file_size: number;
  upload_id: string;
  uploaded_file_parts: MultipartUploadPart[];
  project_name?: string;
  mime_type?: string;
}

export interface UploadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

export interface FileUploadOptions {
  file: File;
  entityType: "trace" | "span";
  entityId: string;
  projectName?: string;
  onProgress?: (progress: UploadProgress) => void;
}
