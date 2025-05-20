import React, { useState } from "react";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import useDatasetCsvUpload from "@/api/datasets/useDatasetCsvUpload";
import { Dataset } from "@/types/datasets";

interface Props {
  open: boolean;
  setOpen: (open: boolean) => void;
  onDatasetCreated?: (dataset: Dataset) => void;
}

const UploadDatasetCsvDialog: React.FC<Props> = ({ open, setOpen, onDatasetCreated }) => {
  const { mutate } = useDatasetCsvUpload();
  const [name, setName] = useState("");
  const [file, setFile] = useState<File>();

  const isValid = name.length > 0 && file;

  const submitHandler = () => {
    if (!file) return;
    mutate(
      { datasetName: name, file },
      {
        onSuccess: (dataset) => {
          setOpen(false);
          if (dataset && onDatasetCreated) {
            onDatasetCreated(dataset);
          }
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Upload dataset CSV</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetName">Name</Label>
          <Input
            id="datasetName"
            placeholder="Dataset name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <div className="flex flex-col gap-2 pb-4">
          <Label htmlFor="datasetFile">CSV file</Label>
          <Input
            id="datasetFile"
            type="file"
            accept=".csv"
            onChange={(e) => setFile(e.target.files?.[0])}
          />
        </div>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button onClick={submitHandler} disabled={!isValid}>
            Upload dataset
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default UploadDatasetCsvDialog;
