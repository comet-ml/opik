import React, { useCallback, useEffect, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import {
  Blocks,
  Check,
  CheckCheck,
  Code2,
  GitCommitVertical,
  Settings2,
  X,
} from "lucide-react";

import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import EvaluationSuiteItemsTab from "@/components/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsTab/EvaluationSuiteItemsTab";
import EditEvaluationSuiteSettingsDialog from "@/components/pages/EvaluationSuiteItemsPage/EditEvaluationSuiteSettingsDialog";
import AddVersionDialog from "@/components/pages-shared/datasets/VersionHistoryTab/AddVersionDialog";
import VersionHistoryTab from "@/components/pages-shared/datasets/VersionHistoryTab/VersionHistoryTab";
import OverrideVersionDialog from "@/components/pages-shared/datasets/OverrideVersionDialog";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import DateTag from "@/components/shared/DateTag/DateTag";
import Loader from "@/components/shared/Loader/Loader";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import TagListRenderer from "@/components/shared/TagListRenderer/TagListRenderer";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tag } from "@/components/ui/tag";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { ToastAction } from "@/components/ui/toast";
import { useToast } from "@/components/ui/use-toast";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { useEvaluationSuiteSavePayload } from "@/hooks/useEvaluationSuiteSavePayload";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import { useClearDraft, useHasDraft } from "@/store/EvaluationSuiteDraftStore";
import { DATASET_STATUS, DATASET_TYPE } from "@/types/datasets";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { AssertionsListTooltipContent } from "@/components/pages-shared/experiments/EvaluationSuiteExperiment/AssertionsListTooltipContent";
import UseEvaluationSuiteDropdown from "./UseEvaluationSuiteDropdown";

const POLLING_INTERVAL_MS = 3000;

function EvaluationSuiteItemsPage(): React.ReactElement {
  const suiteId = useSuiteIdFromURL();

  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [settingsDialogOpen, setSettingsDialogOpen] = useState(false);
  const [pendingVersionData, setPendingVersionData] = useState<{
    tags?: string[];
    changeDescription?: string;
  } | null>(null);

  const queryClient = useQueryClient();
  const hasDraft = useHasDraft();
  const clearDraft = useClearDraft();
  const { toast } = useToast();
  const { navigate: navigateToExperiment } = useNavigateToExperiment();
  const { loadPlayground } = useLoadPlayground();

  const { mutate: updateSuite } = useDatasetUpdateMutation();

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

  const datasetType = suite?.type;
  const isEvaluationSuite = datasetType === DATASET_TYPE.EVALUATION_SUITE;
  const latestVersion = suite?.latest_version;

  const { data: versionsData } = useDatasetVersionsList(
    { datasetId: suiteId, page: 1, size: 1 },
    { enabled: isEvaluationSuite },
  );
  const latestVersionData = versionsData?.content?.[0];
  const versionEvaluators = latestVersionData?.evaluators ?? [];
  const { buildPayload } = useEvaluationSuiteSavePayload({
    suiteId,
    suite,
    versionEvaluators,
  });

  const effectiveAssertions = useEffectiveSuiteAssertions(suiteId);

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

  const showSuccessToast = useCallback(
    (versionId?: string) => {
      toast({
        title: "New version created",
        description:
          "Your changes have been saved as a new version. You can now use it to run experiments in the SDK or the Playground.",
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText="Run experiment in the SDK"
            key="sdk"
            onClick={() =>
              navigateToExperiment({
                newExperiment: true,
                datasetName: suite?.name,
              })
            }
          >
            <Code2 className="size-4" />
            Run experiment in the SDK
          </ToastAction>,
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText="Run experiment in the Playground"
            key="playground"
            onClick={() =>
              loadPlayground({
                datasetId: suiteId,
                datasetVersionId: versionId,
              })
            }
          >
            <Blocks className="size-4" />
            Run experiment in the Playground
          </ToastAction>,
        ],
      });
    },
    [toast, navigateToExperiment, loadPlayground, suite?.name, suiteId],
  );

  const changesMutation = useDatasetItemChangesMutation({
    onConflict: () => {
      setOverrideDialogOpen(true);
    },
  });

  const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
    if (changesMutation.isPending) return;

    changesMutation.mutate(buildPayload({ tags, changeDescription }), {
      onSuccess: async (version) => {
        setAddVersionDialogOpen(false);
        showSuccessToast(version?.id);
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ["dataset-items", { datasetId: suiteId }],
          }),
          queryClient.invalidateQueries({
            queryKey: ["dataset-versions"],
          }),
        ]);
        clearDraft();
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
      buildPayload({ ...pendingVersionData, override: true }),
      {
        onSuccess: async (version) => {
          setAddVersionDialogOpen(false);
          setOverrideDialogOpen(false);
          setPendingVersionData(null);
          showSuccessToast(version?.id);
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: ["dataset-items", { datasetId: suiteId }],
            }),
            queryClient.invalidateQueries({
              queryKey: ["dataset-versions"],
            }),
          ]);
          clearDraft();
        },
      },
    );
  };

  const handleDiscardChanges = () => {
    clearDraft();
    setDiscardDialogOpen(false);
  };

  const handleAddTag = (newTag: string) => {
    updateSuite({
      dataset: {
        ...suite,
        id: suiteId,
        tags: [...(suite?.tags ?? []), newTag],
      },
    });
  };

  const handleDeleteTag = (tag: string) => {
    updateSuite({
      dataset: {
        ...suite,
        id: suiteId,
        tags: (suite?.tags ?? []).filter((t) => t !== tag),
      },
    });
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
        description={`Discarding will remove all unsaved edits to this ${
          isEvaluationSuite ? "evaluation suite" : "dataset"
        }. This action can't be undone. Are you sure you want to continue?`}
        confirmText="Discard changes"
        confirmButtonVariant="destructive"
      />
      <OverrideVersionDialog
        open={overrideDialogOpen}
        setOpen={setOverrideDialogOpen}
        onConfirm={handleOverrideConfirm}
      />
      <EditEvaluationSuiteSettingsDialog
        open={settingsDialogOpen}
        setOpen={setSettingsDialogOpen}
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
              {suite?.name ??
                (isEvaluationSuite ? "Evaluation suite" : "Dataset")}
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
            <UseEvaluationSuiteDropdown
              datasetName={suite?.name}
              datasetId={suiteId}
              datasetVersionId={latestVersion?.id}
              isEvalSuite={isEvaluationSuite}
            />
            {isEvaluationSuite && (
              <Button
                variant="outline"
                size="sm"
                className="gap-1.5"
                onClick={() => setSettingsDialogOpen(true)}
              >
                <Settings2 className="size-3.5 shrink-0" />
                Settings
              </Button>
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
          {isEvaluationSuite && effectiveAssertions.length > 0 && (
            <Tooltip>
              <TooltipTrigger asChild>
                <div className="flex shrink-0 cursor-pointer items-center gap-1 rounded bg-thread-active px-1.5 py-0.5">
                  <CheckCheck className="size-3 text-muted-foreground" />
                  <span className="comet-body-s-accented text-muted-foreground">
                    {effectiveAssertions.length} global assertion
                    {effectiveAssertions.length !== 1 ? "s" : ""}
                  </span>
                </div>
              </TooltipTrigger>
              <TooltipPortal>
                <TooltipContent
                  side="bottom"
                  collisionPadding={16}
                  className="max-w-fit p-0"
                >
                  <AssertionsListTooltipContent
                    assertions={effectiveAssertions}
                  />
                </TooltipContent>
              </TooltipPortal>
            </Tooltip>
          )}
          <Separator orientation="vertical" className="ml-1.5 mt-1 h-4" />
          <TagListRenderer
            tags={suite?.tags ?? []}
            onAddTag={handleAddTag}
            onDeleteTag={handleDeleteTag}
            align="start"
            className="min-h-0 w-auto"
          />
        </div>
      </div>
      <Tabs value={tab || "items"} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            Items
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
            datasetType={datasetType}
            suiteAssertions={effectiveAssertions}
            onOpenSettings={() => setSettingsDialogOpen(true)}
          />
        </TabsContent>
        <TabsContent value="version-history">
          <VersionHistoryTab datasetId={suiteId} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

export default EvaluationSuiteItemsPage;
