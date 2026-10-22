import isInteger from "lodash/isInteger";
import isPlainObject from "lodash/isPlainObject";
import isString from "lodash/isString";
import useOllieArtifact from "@/hooks/useOllieArtifact";
import { ArtifactProposal, ArtifactRejection } from "@/types/assistant-sidebar";
import { LATEST_KIT_VERSION } from "@/shared/SandboxedView/kit/loadKit";

export const ANNOTATOR_VIEW_ARTIFACT = "annotator_view";

const MAX_HTML_LENGTH = 500_000;
const MAX_SUMMARY_LENGTH = 1000;

export type AnnotatorViewScope = "trace" | "thread";

export type AnnotatorViewContext = {
  projectId: string;
  queueId: string | null;
  scope: AnnotatorViewScope;
  queueName: string;
  instructions: string;
  feedbackDefinitions: string[];
  currentHtml: string | null;
  currentSummary: string | null;
  fixError: string | null;
};

type AnnotatorViewTarget = {
  projectId: string;
  queueId: string | null;
  scope: AnnotatorViewScope;
};

type AnnotatorViewPayload = {
  html: string;
  kitVersion: number;
  summary: string;
};

const isTarget = (value: unknown): value is AnnotatorViewTarget =>
  isPlainObject(value) &&
  isString((value as AnnotatorViewTarget).projectId) &&
  ((value as AnnotatorViewTarget).queueId === null ||
    isString((value as AnnotatorViewTarget).queueId)) &&
  isString((value as AnnotatorViewTarget).scope);

const isPayload = (value: unknown): value is AnnotatorViewPayload => {
  if (!isPlainObject(value)) return false;
  const { html, kitVersion, summary } = value as AnnotatorViewPayload;
  return (
    isString(html) &&
    Boolean(html.trim()) &&
    html.length <= MAX_HTML_LENGTH &&
    isString(summary) &&
    summary.length <= MAX_SUMMARY_LENGTH &&
    isInteger(kitVersion) &&
    kitVersion >= 1 &&
    kitVersion <= LATEST_KIT_VERSION
  );
};

type UseAnnotatorViewArtifactParams = {
  enabled: boolean;
  projectId: string;
  queueId?: string;
  scope: AnnotatorViewScope;
  onApply: (html: string, kitVersion: number, summary: string) => void;
  onReject?: (reason: ArtifactRejection) => void;
};

const useAnnotatorViewArtifact = ({
  enabled,
  projectId,
  queueId,
  scope,
  onApply,
  onReject,
}: UseAnnotatorViewArtifactParams) => {
  const check = ({
    target,
    payload,
  }: ArtifactProposal): ArtifactRejection | null => {
    if (!isTarget(target)) return "invalid";
    if (
      target.projectId !== projectId ||
      target.queueId !== (queueId ?? null) ||
      target.scope !== scope
    ) {
      return "target_mismatch";
    }
    if (!isPayload(payload)) return "invalid";
    onApply(payload.html, payload.kitVersion, payload.summary);
    return null;
  };

  useOllieArtifact(
    ANNOTATOR_VIEW_ARTIFACT,
    (proposal) => {
      const reason = check(proposal);
      if (reason) onReject?.(reason);
      return reason;
    },
    enabled,
  );
};

export default useAnnotatorViewArtifact;
