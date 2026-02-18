import { useState, useEffect } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { X, Check, GitCommitVertical } from "lucide-react";
import { AxiosError } from "axios";

import Loader from "@/components/shared/Loader/Loader";
import DateTag from "@/components/shared/DateTag/DateTag";
import useDatasetById from "@/api/datasets/useDatasetById";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import EvaluationSuiteItemsTab from "@/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/EvaluationSuiteItemsTab";
import VersionHistoryTab from "@/components/pages/DatasetItemsPage/VersionHistoryTab/VersionHistoryTab";
import AddVersionDialog from "@/components/pages/DatasetItemsPage/VersionHistoryTab/AddVersionDialog";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { DATASET_STATUS } from "@/types/datasets";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import {
  useHasDraft,
  useClearDraft,
  useGetFullChangesPayload,
} from "@/store/EvaluationSuiteDraftStore";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import OverrideVersionDialog from "@/components/pages/DatasetItemsPage/OverrideVersionDialog";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import { useToast } from "@/components/ui/use-toast";
import BehaviorsSection from "@/components/pages/EvaluationSuiteItemsPage/BehaviorsSection/BehaviorsSection";

const POLLING_INTERVAL_MS = 3000;

const EvaluationSuiteItemsPage = () => {
  const suiteId = useSuiteIdFromURL();

  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [pendingVersionData, setPendingVersionData] = useState<{
    tags?: string[];
    changeDescription?: string;
  } | null>(null);

  const hasDraft = useHasDraft();
  const clearDraft = useClearDraft();
  const getFullChangesPayload = useGetFullChangesPayload();
  const { toast } = useToast();

  const { data: suite, isPending } = useDatasetById(
    { datasetId: suiteId },
    {
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  const latestVersion = suite?.latest_version;

  useEffect(() => {
    return clearDraft;
  }, [suiteId, clearDraft]);

  const { DialogComponent } = useNavigationBlocker({
    condition: hasDraft,
    title: "Unsaved changes",
    description:
      "You have unsaved draft changes. Are you sure you want to leave?",
    confirmText: "Leave without saving",
    cancelText: "Stay",
  });

  const showSuccessToast = () => {
    toast({
      title: "New version created",
      description:
        "Your evaluation suite changes have been saved as a new version.",
    });
  };

  const changesMutation = useDatasetItemChangesMutation({
    onConflict: () => {
      setOverrideDialogOpen(true);
    },
  });

  const buildMutationPayload = (
    tags?: string[],
    changeDescription?: string,
    override = false,
  ) => {
    // Pass empty array as originalEvaluators — BE evaluator endpoints
    // don't exist yet; once OPIK-4224 lands, original evaluators will
    // come from the dataset version query and be passed here.
    const changes = getFullChangesPayload([]);
    return {
      datasetId: suiteId,
      payload: {
        added_items: changes.addedItems,
        edited_items: changes.editedItems,
        deleted_ids: changes.deletedIds,
        base_version: suite?.latest_version?.id ?? "",
        tags,
        change_description: changeDescription,
        evaluators:
          changes.evaluators.length > 0 ? changes.evaluators : undefined,
        execution_policy: changes.execution_policy ?? undefined,
      },
      override,
    };
  };

  const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
    if (changesMutation.isPending) return;

    changesMutation.mutate(buildMutationPayload(tags, changeDescription), {
      onSuccess: () => {
        clearDraft();
        setAddVersionDialogOpen(false);
        showSuccessToast();
      },
      onError: (error) => {
        if ((error as AxiosError).response?.status === 409) {
          setPendingVersionData({ tags, changeDescription });
        }
      },
    });
  };

  const handleOverrideConfirm = () => {
    if (!pendingVersionData) return;

    changesMutation.mutate(
      buildMutationPayload(
        pendingVersionData.tags,
        pendingVersionData.changeDescription,
        true,
      ),
      {
        onSuccess: () => {
          clearDraft();
          setAddVersionDialogOpen(false);
          setOverrideDialogOpen(false);
          setPendingVersionData(null);
          showSuccessToast();
        },
      },
    );
  };

  const handleDiscardChanges = () => {
    clearDraft();
    setDiscardDialogOpen(false);
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <AddVersionDialog
        open={addVersionDialogOpen}
        setOpen={setAddVersionDialogOpen}
        onConfirm={handleSaveChanges}
        isSubmitting={changesMutation.isPending}
      />
      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscardChanges}
        title="Discard changes"
        description="Discarding will remove all unsaved edits to this evaluation suite. This action can't be undone. Are you sure you want to continue?"
        confirmText="Discard changes"
        confirmButtonVariant="destructive"
      />
      <OverrideVersionDialog
        open={overrideDialogOpen}
        setOpen={setOverrideDialogOpen}
        onConfirm={handleOverrideConfirm}
      />
      {DialogComponent}
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {hasDraft && (
              <Tag variant="orange" size="md">
                Draft
              </Tag>
            )}
            <h1 className="comet-title-l truncate break-words">
              {suite?.name ?? "Evaluation suite"}
            </h1>
          </div>
          <div className="flex items-center gap-2">
            {hasDraft && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setDiscardDialogOpen(true)}
                >
                  <X className="mr-1 size-4" />
                  Discard changes
                </Button>
                <Button
                  variant="default"
                  size="sm"
                  onClick={() => setAddVersionDialogOpen(true)}
                >
                  <Check className="mr-1 size-4" />
                  Save changes
                </Button>
              </>
            )}
          </div>
        </div>
        {suite?.description && (
          <div className="-mt-3 mb-4 text-muted-slate">{suite.description}</div>
        )}
        <div className="flex gap-2 overflow-x-auto">
          {suite?.created_at && (
            <DateTag
              date={suite.created_at}
              resource={RESOURCE_TYPE.evaluationSuite}
            />
          )}
          {latestVersion && (
            <>
              <Tag
                size="md"
                variant="transparent"
                className="flex shrink-0 items-center gap-1"
              >
                <GitCommitVertical className="size-3 text-green-500" />
                {latestVersion.version_name}
              </Tag>
              {latestVersion.tags?.map((tag) => (
                <ColoredTag
                  key={tag}
                  label={tag}
                  size="md"
                  IconComponent={GitCommitVertical}
                />
              ))}
            </>
          )}
        </div>
      </div>
      <Tabs value={tab || "items"} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Items
          </TabsTrigger>
          <TabsTrigger variant="underline" value="behaviors">
            Behaviors
          </TabsTrigger>
          <TabsTrigger variant="underline" value="version-history">
            Version history
          </TabsTrigger>
        </TabsList>
        <TabsContent value="items">
          <EvaluationSuiteItemsTab
            datasetId={suiteId}
            datasetName={suite?.name}
            datasetStatus={suite?.status}
          />
        </TabsContent>
        <TabsContent value="behaviors">
          <BehaviorsSection datasetId={suiteId} />
        </TabsContent>
        <TabsContent value="version-history">
          <VersionHistoryTab datasetId={suiteId} />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default EvaluationSuiteItemsPage;
