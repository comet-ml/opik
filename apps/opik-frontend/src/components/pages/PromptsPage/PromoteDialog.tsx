import React from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { DeploymentVersion } from "@/types/blueprints";
import EnvironmentBadge from "./EnvironmentBadge";

type PromoteDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  version: DeploymentVersion | null;
  currentProdVersion: number;
  onConfirm: () => void;
  isLoading?: boolean;
};

const PromoteDialog: React.FC<PromoteDialogProps> = ({
  open,
  setOpen,
  version,
  currentProdVersion,
  onConfirm,
  isLoading,
}) => {
  if (!version) return null;

  const isPromotion = version.version_number > currentProdVersion;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>
            {isPromotion ? "Promote to PROD" : "Rollback PROD"}
          </DialogTitle>
          <DialogDescription>
            {isPromotion ? (
              <>
                This will move <EnvironmentBadge env="prod" /> from v
                {currentProdVersion} to v{version.version_number}.
              </>
            ) : (
              <>
                This will roll back <EnvironmentBadge env="prod" /> from v
                {currentProdVersion} to v{version.version_number}.
              </>
            )}
          </DialogDescription>
        </DialogHeader>

        <div className="rounded-md border bg-muted/30 p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">v{version.version_number}</span>
            <span className="text-xs text-muted-slate">
              {version.change_type}
            </span>
          </div>
          {version.change_summary && (
            <p className="mt-1 text-sm text-muted-slate">
              {version.change_summary}
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            variant={isPromotion ? "default" : "destructive"}
            onClick={onConfirm}
            disabled={isLoading}
          >
            {isPromotion ? "Promote" : "Rollback"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default PromoteDialog;
