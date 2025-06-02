import React, { useRef, useState } from "react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { File as FileIcon, CircleX, Check, X } from "lucide-react";

export type UploadFieldProps = {
  /** Called when a file is selected or cleared */
  onFileSelect: (file: File | null) => void;
  /** Error message to display under the file entry */
  errorText?: string;
  /** Success message to display under the file entry */
  successText?: string;
  /** File types to accept (e.g. ".csv") */
  accept?: string;
  /** Disable the upload control */
  disabled?: boolean;
};

const UploadField: React.FC<UploadFieldProps> = ({
  onFileSelect,
  errorText,
  successText,
  accept = ".csv",
  disabled = false,
}) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);

  const handleSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    onFileSelect(f);
  };

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    if (disabled) return;
    const f = e.dataTransfer.files?.[0] ?? null;
    setFile(f);
    onFileSelect(f);
  };

  const clearFile = () => {
    setFile(null);
    onFileSelect(null);
  };

  return (
    <>
      {!file ? (
        <div
          className={cn(
            "group relative flex h-32 items-center justify-center rounded border-2 border-dashed",
            disabled
              ? "cursor-not-allowed opacity-50 border-border"
              : "cursor-pointer border-border hover:border-primary",
          )}
          onDragOver={(e) => !disabled && e.preventDefault()}
          onDrop={disabled ? undefined : handleDrop}
          onClick={() => !disabled && fileInputRef.current?.click()}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept={accept}
            className="hidden"
            disabled={disabled}
            onChange={handleSelect}
          />
          <FileIcon className="mr-2 h-5 w-5 text-muted-foreground" />
          <div className="flex items-center gap-1">
            <span className="text-sm text-muted-foreground">
              Drop a file to upload or
            </span>
            <Button size="sm" disabled={disabled}>
              Browse
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="flex items-center justify-between p-2 border border-border rounded">
            <div className="flex items-center gap-2">
              <FileIcon className="h-5 w-5 text-muted-foreground" />
              <span className="text-sm">{file.name}</span>
            </div>
            <X
              className="h-4 w-4 cursor-pointer text-muted-foreground"
              onClick={clearFile}
            />
          </div>
          {errorText && (
            <div className="flex items-center gap-1 mt-1 text-sm text-destructive">
              <CircleX className="h-4 w-4" />
              <span>{errorText}</span>
            </div>
          )}
          {!errorText && successText && (
            <div className="flex items-center gap-1 mt-1 text-sm text-success">
              <Check className="h-4 w-4" />
              <span>{successText}</span>
            </div>
          )}
        </>
      )}
    </>
  );
};

export default UploadField;