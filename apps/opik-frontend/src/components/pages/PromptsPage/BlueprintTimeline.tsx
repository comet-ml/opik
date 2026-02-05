import React, { useState } from "react";
import { DeploymentVersion, BlueprintHistory } from "@/types/blueprints";
import BlueprintTimelineItem from "./BlueprintTimelineItem";
import PromoteDialog from "./PromoteDialog";
import VersionDetailPage from "./VersionDetailPage";
import usePromoteToProd from "@/api/blueprints/usePromoteToProd";
import { useToast } from "@/components/ui/use-toast";

type BlueprintTimelineProps = {
  blueprintId: string;
  history: BlueprintHistory;
};

const BlueprintTimeline: React.FC<BlueprintTimelineProps> = ({
  blueprintId,
  history,
}) => {
  const { toast } = useToast();
  const [selectedVersion, setSelectedVersion] =
    useState<DeploymentVersion | null>(null);
  const [promoteDialogOpen, setPromoteDialogOpen] = useState(false);
  const [viewingVersion, setViewingVersion] = useState<number | null>(null);

  const promoteMutation = usePromoteToProd();

  const prodVersion = history.pointers.prod || 0;

  const handlePromoteClick = (version: DeploymentVersion) => {
    setSelectedVersion(version);
    setPromoteDialogOpen(true);
  };

  const handleViewClick = (version: DeploymentVersion) => {
    setViewingVersion(version.version_number);
  };

  const handleBackFromDetail = () => {
    setViewingVersion(null);
  };

  const handleConfirmPromote = async () => {
    if (!selectedVersion) return;

    try {
      await promoteMutation.mutateAsync({
        blueprintId,
        versionNumber: selectedVersion.version_number,
      });

      const isPromotion = selectedVersion.version_number > prodVersion;

      toast({
        title: isPromotion ? "Promoted to PROD" : "Rolled back PROD",
        description: `PROD is now at v${selectedVersion.version_number}`,
      });

      setPromoteDialogOpen(false);
      setSelectedVersion(null);
    } catch {
      toast({
        title: "Error",
        description: "Failed to update PROD pointer",
        variant: "destructive",
      });
    }
  };

  // Get all environments pointing to each version
  const getEnvsForVersion = (versionNumber: number): string[] => {
    return Object.entries(history.pointers)
      .filter(([, v]) => v === versionNumber)
      .map(([env]) => env);
  };

  // Show version detail page
  if (viewingVersion !== null) {
    return (
      <VersionDetailPage
        blueprintId={blueprintId}
        versionNumber={viewingVersion}
        onBack={handleBackFromDetail}
      />
    );
  }

  if (history.versions.length === 0) {
    return (
      <div className="flex min-h-48 flex-col items-center justify-center text-muted-slate">
        <p>No deployment versions yet.</p>
        <p className="text-sm">
          Run the optimizer to create your first version.
        </p>
      </div>
    );
  }

  return (
    <div className="relative">
      <div className="space-y-0">
        {history.versions.map((version, index) => (
          <div key={version.id} className="relative">
            {/* Hide the line extending below the last item */}
            {index === history.versions.length - 1 && (
              <div className="absolute bottom-0 left-[4.5rem] top-10 z-10 w-2 bg-background" />
            )}
            <BlueprintTimelineItem
              version={version}
              envs={getEnvsForVersion(version.version_number)}
              onPromoteClick={() => handlePromoteClick(version)}
              onViewClick={() => handleViewClick(version)}
              canPromote={version.version_number !== prodVersion}
            />
          </div>
        ))}
      </div>

      <PromoteDialog
        open={promoteDialogOpen}
        setOpen={setPromoteDialogOpen}
        version={selectedVersion}
        currentProdVersion={prodVersion}
        onConfirm={handleConfirmPromote}
        isLoading={promoteMutation.isPending}
      />
    </div>
  );
};

export default BlueprintTimeline;
