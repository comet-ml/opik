import { useCallback, useEffect, useMemo, useState } from "react";
import isEqual from "lodash/isEqual";
import { AnnotationQueueAnnotatorView } from "@/types/annotation-queues";
import { LATEST_KIT_VERSION } from "@/shared/SandboxedView/kit/loadKit";

export type AnnotatorViewDraft = {
  html: string;
  kitVersion: number;
  summary: string;
};

type Scope = "trace" | "thread";

const EMPTY_DRAFT: AnnotatorViewDraft = {
  html: "",
  kitVersion: LATEST_KIT_VERSION,
  summary: "",
};

type UseAnnotatorViewDraftParams = {
  view?: AnnotationQueueAnnotatorView;
  isThreadScope: boolean;
};

const toDraft = (view?: AnnotationQueueAnnotatorView): AnnotatorViewDraft => ({
  html: view?.html ?? "",
  kitVersion: view?.kit_version ?? LATEST_KIT_VERSION,
  summary: view?.summary ?? "",
});

const useAnnotatorViewDraft = ({
  view,
  isThreadScope,
}: UseAnnotatorViewDraftParams) => {
  const scope: Scope = isThreadScope ? "thread" : "trace";
  const [initialScope] = useState(scope);
  const [initialDraft, setInitialDraft] = useState<AnnotatorViewDraft>(() =>
    toDraft(view),
  );
  const [hasHydratedView, setHasHydratedView] = useState(Boolean(view));
  const [drafts, setDrafts] = useState<Record<Scope, AnnotatorViewDraft>>(
    () => ({
      trace: initialScope === "trace" ? initialDraft : EMPTY_DRAFT,
      thread: initialScope === "thread" ? initialDraft : EMPTY_DRAFT,
    }),
  );

  useEffect(() => {
    if (!view || hasHydratedView) return;
    const hydrated = toDraft(view);
    setHasHydratedView(true);
    setInitialDraft(hydrated);
    setDrafts((prev) => ({ ...prev, [initialScope]: hydrated }));
  }, [view, hasHydratedView, initialScope]);
  const draft = drafts[scope];
  const saved = scope === initialScope ? initialDraft : EMPTY_DRAFT;

  const apply = useCallback(
    (html: string, kitVersion: number, summary: string) =>
      setDrafts((prev) => ({
        ...prev,
        [scope]: { html, kitVersion, summary },
      })),
    [scope],
  );

  const reset = useCallback(
    () => setDrafts((prev) => ({ ...prev, [scope]: EMPTY_DRAFT })),
    [scope],
  );

  const hasView = Boolean(draft.html.trim());

  const annotatorView = useMemo<AnnotationQueueAnnotatorView>(
    () =>
      hasView
        ? {
            html: draft.html,
            kit_version: draft.kitVersion,
            ...(draft.summary ? { summary: draft.summary } : {}),
          }
        : {},
    [hasView, draft],
  );

  return {
    draft,
    hasView,
    apply,
    reset,
    annotatorView,
    isUnsaved: !isEqual(draft, saved),
  };
};

export default useAnnotatorViewDraft;
