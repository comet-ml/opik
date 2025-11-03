import React, { useCallback, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Check,
  X,
  CloudUpload,
  FileSpreadsheet,
  TriangleAlert,
} from "lucide-react";

export type UploadFieldProps = {
  onFileSelect: (file: File | undefined) => void;
  description?: string;
  errorText?: string;
  successText?: string;
  accept?: string;
  disabled?: boolean;
};

const UploadField: React.FC<UploadFieldProps> = ({
  onFileSelect,
  description = "Drop a file to upload or",
  errorText,
  successText,
  accept = ".csv",
  disabled = false,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | undefined>(undefined);
  const [isDragOver, setIsDragOver] = useState(false);

  const handleSelect = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const f = e.target.files?.[0] ?? undefined;
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      setFile(f);
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
      setFile(f);
      onFileSelect(f);
    },
    [disabled, onFileSelect],
  );

  const clearFile = useCallback(() => {
    setFile(undefined);
    onFileSelect(undefined);
  }, [onFileSelect]);

  return (
    <>
      <div
        className={cn(
          "group relative flex h-20 items-center gap-2 justify-center border border-dashed text-foreground",
          {
            "cursor-not-allowed opacity-50": disabled,
            "border-primary": isDragOver && !disabled,
          },
        )}
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
          accept={accept}
          className="hidden"
          disabled={disabled}
          onChange={handleSelect}
        />
        <CloudUpload className="-mr-0.5 size-[18px] " />
        <div className="flex items-center gap-2">
          <span className="comet-body-s-accented">{description}</span>
          <Button
            type="button"
            size="sm"
            variant="secondary"
            disabled={disabled}
            onClick={() => !disabled && fileInputRef.current?.click()}
          >
            Browse
          </Button>
        </div>
      </div>
      {file && (
        <div className="mt-5 overflow-hidden rounded-sm bg-primary-foreground p-3">
          <div className="flex max-w-full flex-nowrap items-center justify-between gap-3 rounded">
            <div className="flex size-10 shrink-0 items-center justify-center rounded-md bg-upload-icon-bg/25">
              <FileSpreadsheet className="size-5 text-muted-foreground" />
            </div>
            <div className="flex min-w-1 flex-auto flex-col justify-center">
              <span className="comet-body-s-accented truncate text-foreground">
                {file.name}
              </span>
              {errorText && (
                <div className="comet-body-xs flex text-destructive">
                  <TriangleAlert className="my-0.5 mr-1 size-3 shrink-0" />
                  {errorText}
                </div>
              )}
              {!errorText && successText && (
                <span className="comet-body-xs flex items-center text-special-button">
                  <Check className="mr-1 size-3 shrink-0" />
                  {successText}
                </span>
              )}
            </div>
            <Button onClick={clearFile} variant="ghost" size="icon">
              <X className="size-4 cursor-pointer text-muted-foreground" />
            </Button>
          </div>
        </div>
      )}
    </>
  );
};

export default UploadField;
