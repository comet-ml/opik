import React from "react";
import { Button } from "@/ui/button";
import { Dialog, DialogContent } from "@/ui/dialog";
import Loader from "@/shared/Loader/Loader";

type CsvUploadDialogProps = {
  open: boolean;
  isCsvMode: boolean;
  onClose: () => void;
};

const CsvUploadDialog: React.FC<CsvUploadDialogProps> = ({
  open,
  isCsvMode,
  onClose,
}) => {
  return (
    <Dialog open={open}>
      <DialogContent className="max-w-sm [&>button.absolute]:hidden">
        <Loader
          className="min-h-40"
          message={
            <div>
              <div className="comet-body-xs-accented text-center">
                {isCsvMode ? "Upload in progress..." : "Processing the CSV"}
              </div>
              {!isCsvMode && (
                <>
                  <div className="comet-body-s mt-2 text-center text-light-slate">
                    This should take less than a minute. <br /> You can safely
                    close this dialog while we work.
                  </div>
                  <div className="mt-4 flex items-center justify-center">
                    <Button onClick={onClose}>Close</Button>
                  </div>
                </>
              )}
            </div>
          }
        />
      </DialogContent>
    </Dialog>
  );
};

export default CsvUploadDialog;
