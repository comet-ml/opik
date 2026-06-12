import React, { useCallback, useRef, useState } from "react";
import { FileTerminal, TriangleAlert, X } from "lucide-react";

import { cn } from "@/lib/utils";
import { DATASET_UPLOAD_ACCEPTED_TYPES } from "@/lib/file";

type DatasetCsvDropzoneProps = {
  uploadFile: File | undefined;
  uploadError: string | undefined;
  onFileSelect: (file: File | undefined) => void;
  onUseSdk: () => void;
  disabled?: boolean;
};

const DatasetCsvDropzone: React.FC<DatasetCsvDropzoneProps> = ({
  uploadFile,
  uploadError,
  onFileSelect,
  onUseSdk,
  disabled = false,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  // Track the latest dropped name so we can show a chip even when the file
  // failed validation (the form hook only keeps valid files in `uploadFile`).
  const [attemptedName, setAttemptedName] = useState<string | undefined>(
    undefined,
  );

  const handleSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const f = e.target.files?.[0] ?? undefined;
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      setAttemptedName(f?.name);
      onFileSelect(f);
    },
    [onFileSelect],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent<HTMLDivElement>) => {
      e.preventDefault();
      setIsDragOver(false);
      if (disabled) return;
      const f = e.dataTransfer.files?.[0] ?? undefined;
      setAttemptedName(f?.name);
      onFileSelect(f);
    },
    [disabled, onFileSelect],
  );

  const clearFile = useCallback(() => {
    setAttemptedName(undefined);
    onFileSelect(undefined);
  }, [onFileSelect]);

  const browse = useCallback(() => {
    if (!disabled) fileInputRef.current?.click();
  }, [disabled]);

  const fileName = uploadFile?.name ?? attemptedName;
  const showChip =
    Boolean(fileName) && (Boolean(uploadFile) || Boolean(uploadError));

  if (showChip) {
    return (
      <div>
        <div className="flex items-center gap-1.5 rounded-md border border-[var(--upload-chip-border)] bg-[var(--upload-chip-bg)] px-2 py-1">
          <button
            type="button"
            onClick={clearFile}
            className="shrink-0 text-light-slate hover:text-foreground"
            aria-label="Remove file"
          >
            <X className="size-3.5" />
          </button>
          <div className="flex size-4 shrink-0 items-center justify-center rounded bg-[var(--upload-chip-icon-bg)]">
            <FileTerminal className="size-2.5 text-foreground" />
          </div>
          <span className="comet-body-s truncate text-foreground">
            {fileName}
          </span>
        </div>
        {uploadError && (
          <div
            role="alert"
            className="comet-body-xs mt-1.5 flex items-center text-destructive"
          >
            <TriangleAlert className="mr-1 size-3 shrink-0" />
            {uploadError}
          </div>
        )}
      </div>
    );
  }

  return (
    <div
      className={cn(
        "group flex min-h-[280px] flex-col items-center justify-center rounded-md border border-dashed border-border p-6 text-foreground transition-colors",
        {
          "cursor-not-allowed opacity-50": disabled,
          "cursor-pointer hover:border-primary hover:bg-toggle-outline-active":
            !disabled,
          "border-primary bg-toggle-outline-active": isDragOver && !disabled,
        },
      )}
      onClick={disabled ? undefined : browse}
      onDragOver={(e) => {
        if (!disabled) {
          e.preventDefault();
          setIsDragOver(true);
        }
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={disabled ? undefined : handleDrop}
    >
      <input
        ref={fileInputRef}
        type="file"
        accept={DATASET_UPLOAD_ACCEPTED_TYPES}
        className="hidden"
        disabled={disabled}
        onChange={handleSelect}
      />
      <div className="flex flex-col items-center gap-1 text-center">
        <p className="comet-body-s">
          Drop file here, or{" "}
          <button
            type="button"
            className="text-primary hover:underline group-hover:underline"
            onClick={(e) => {
              e.stopPropagation();
              browse();
            }}
            disabled={disabled}
          >
            Browse files
          </button>
        </p>
        <p className="comet-body-xs text-muted-slate">
          CSV or JSON. Prefer to use code instead?{" "}
          <button
            type="button"
            className="underline hover:text-foreground"
            onClick={(e) => {
              e.stopPropagation();
              onUseSdk();
            }}
          >
            Use SDK
          </button>
        </p>
      </div>
    </div>
  );
};

export default DatasetCsvDropzone;
