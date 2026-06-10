import React from "react";

import UploadField from "@/shared/UploadField/UploadField";
import {
  DATASET_UPLOAD_ACCEPTED_TYPES,
  formatToHumanLabel,
  UploadFormat,
} from "@/lib/file";

type DatasetUploadFieldProps = {
  uploadFile: File | undefined;
  uploadFormat: UploadFormat | undefined;
  uploadError: string | undefined;
  onFileSelect: (file: File | undefined) => void;
  disabled?: boolean;
};

const DatasetUploadField: React.FC<DatasetUploadFieldProps> = ({
  uploadFile,
  uploadFormat,
  uploadError,
  onFileSelect,
  disabled,
}) => (
  <UploadField
    disabled={disabled}
    description="Drop a CSV or JSON file to upload or"
    accept={DATASET_UPLOAD_ACCEPTED_TYPES}
    onFileSelect={onFileSelect}
    errorText={uploadError}
    successText={
      uploadFile && !uploadError && uploadFormat
        ? `${formatToHumanLabel(uploadFormat)} file ready to upload`
        : undefined
    }
  />
);

export default DatasetUploadField;
