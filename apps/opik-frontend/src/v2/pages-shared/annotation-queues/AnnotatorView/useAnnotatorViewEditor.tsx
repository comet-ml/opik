import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { v4 as uuidv4 } from "uuid";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import usePluginsStore from "@/store/PluginsStore";
import useOllieCredits from "@/api/ollie/useOllieCredits";
import useCanProposeArtifacts from "@/hooks/useCanProposeArtifacts";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { AnnotationQueueAnnotatorView } from "@/types/annotation-queues";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { useToast } from "@/ui/use-toast";
import { ArtifactRejection } from "@/types/assistant-sidebar";
import { formatThreadDateRange } from "@/lib/annotation-queues";
import { SandboxedViewSample } from "@/shared/SandboxedView/SandboxedViewPreview";
import useAnnotatorTracePreviewSamples from "./useAnnotatorTracePreviewSamples";
import useAnnotatorThreadPreviewSamples from "./useAnnotatorThreadPreviewSamples";
import useAnnotatorViewDraft from "./useAnnotatorViewDraft";
import useAnnotatorViewArtifact, {
  ANNOTATOR_VIEW_ARTIFACT,
  AnnotatorViewContext,
} from "./useAnnotatorViewArtifact";
import {
  toAnnotatorThreadViewData,
  toAnnotatorTraceViewData,
} from "./annotatorViewData";
import DefaultThreadView from "./DefaultThreadView";
import DefaultTraceView from "./DefaultTraceView";

const DRAFTING_TIMEOUT_MS = 120_000;

export type AnnotatorViewOllieState =
  | "none"
  | "credits"
  | "no-data"
  | "drafting"
  | "ready";

type UseAnnotatorViewEditorParams = {
  open: boolean;
  view?: AnnotationQueueAnnotatorView;
  projectId: string;
  queueId?: string;
  queueName: string;
  instructions: string;
  feedbackDefinitionNames: string[];
  isThreadScope: boolean;
};

const useAnnotatorViewEditor = ({
  open,
  view,
  projectId,
  queueId,
  queueName,
  instructions,
  feedbackDefinitionNames,
  isThreadScope,
}: UseAnnotatorViewEditorParams) => {
  const scope = isThreadScope ? "thread" : "trace";
  const { draft, hasView, apply, reset, annotatorView, isUnsaved } =
    useAnnotatorViewDraft({ view, isThreadScope });

  const traceSamples = useAnnotatorTracePreviewSamples({
    projectId,
    queueId,
    isThreadScope,
    enabled: open,
  });
  const threadSamples = useAnnotatorThreadPreviewSamples({
    projectId,
    queueId,
    enabled: open && isThreadScope,
  });
  const { sampleIndex, setSampleIndex, fromProject, isPending } = isThreadScope
    ? threadSamples
    : traceSamples;

  const samples = useMemo<SandboxedViewSample[]>(
    () =>
      isThreadScope
        ? threadSamples.threads.map((thread) => {
            const traces =
              thread.id === threadSamples.thread?.id
                ? threadSamples.traces
                : undefined;
            return {
              key: thread.id,
              label: `Thread: ${
                formatThreadDateRange(thread.start_time, thread.end_time) ??
                thread.id
              }`,
              data: traces
                ? toAnnotatorThreadViewData(thread, traces)
                : undefined,
              fallback: traces ? <DefaultThreadView traces={traces} /> : null,
            };
          })
        : traceSamples.samples.map((trace) => ({
            key: trace.id,
            label: `Trace: ${trace.name}`,
            data: toAnnotatorTraceViewData(trace),
            fallback: <DefaultTraceView trace={trace} />,
          })),
    [
      isThreadScope,
      threadSamples.threads,
      threadSamples.thread,
      threadSamples.traces,
      traceSamples.samples,
    ],
  );

  const assistantAvailable = usePluginsStore((state) =>
    Boolean(state.AssistantSidebar),
  );
  const canProposeArtifacts = useCanProposeArtifacts();
  const ollieEnabled =
    useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED) &&
    assistantAvailable &&
    canProposeArtifacts;
  const { data: hasCredits } = useOllieCredits();

  const [isDrafting, setIsDrafting] = useState(false);
  const [flashKey, setFlashKey] = useState(0);
  const draftingTimer = useRef<ReturnType<typeof setTimeout>>();
  const stopDrafting = useCallback(() => {
    clearTimeout(draftingTimer.current);
    setIsDrafting(false);
  }, []);
  useEffect(() => () => clearTimeout(draftingTimer.current), []);

  const onApply = useCallback(
    (html: string, kitVersion: number, summary: string) => {
      apply(html, kitVersion, summary);
      stopDrafting();
      setFlashKey((key) => key + 1);
    },
    [apply, stopDrafting],
  );

  const { toast } = useToast();
  const onReject = useCallback(
    (reason: ArtifactRejection) => {
      stopDrafting();
      toast({
        title: "Ollie's view was not applied",
        description:
          reason === "target_mismatch"
            ? "It was written for a different queue or scope. Ask Ollie again from this queue."
            : "It was not a valid view. Ask Ollie to try again.",
        variant: "destructive",
      });
    },
    [stopDrafting, toast],
  );

  useAnnotatorViewArtifact({
    enabled: open && ollieEnabled,
    projectId,
    queueId,
    scope,
    onApply,
    onReject,
  });

  let ollieState: AnnotatorViewOllieState = "ready";
  if (!ollieEnabled) ollieState = "none";
  else if (hasCredits === false) ollieState = "credits";
  else if (!isPending && !samples.length) ollieState = "no-data";
  else if (isDrafting) ollieState = "drafting";

  const startOllie = (fixError?: string) => {
    const action = fixError ? "fix" : hasView ? "edit" : "design";
    const entryId = uuidv4();
    trackEvent(OpikEvent.ANNOTATION_QUEUE_VIEW_OLLIE_CLICKED, {
      entry_id: entryId,
      project_id: projectId,
      queue_id: queueId ?? null,
      action,
    });
    const messages = {
      design: `I want to customize the annotator view for “${
        queueName || "this queue"
      }”. Ask me what annotators should see.`,
      edit: "I want to change the current annotator view for this queue. Ask me what to adjust.",
      fix: `Fix the custom view. It fails on one of the sample ${scope}s.`,
    };
    const context: AnnotatorViewContext = {
      projectId,
      queueId: queueId ?? null,
      scope,
      queueName,
      instructions,
      feedbackDefinitions: feedbackDefinitionNames,
      currentHtml: hasView ? draft.html : null,
      currentSummary: hasView && draft.summary ? draft.summary : null,
      fixError: fixError ?? null,
    };
    window.opikBridge?.startConversation(messages[action], {
      origin: "annotator_view",
      entryId,
      artifact: { kind: ANNOTATOR_VIEW_ARTIFACT, context },
    });
    setIsDrafting(true);
    clearTimeout(draftingTimer.current);
    draftingTimer.current = setTimeout(stopDrafting, DRAFTING_TIMEOUT_MS);
  };

  return {
    draft,
    hasView,
    isUnsaved,
    annotatorView,
    reset,
    samples,
    sampleIndex,
    setSampleIndex,
    isSamplesPending: isPending,
    fromProject,
    scope,
    hasQueue: Boolean(queueId),
    ollieState,
    startOllie,
    flashKey,
  };
};

export type AnnotatorViewEditor = ReturnType<typeof useAnnotatorViewEditor>;

export default useAnnotatorViewEditor;
