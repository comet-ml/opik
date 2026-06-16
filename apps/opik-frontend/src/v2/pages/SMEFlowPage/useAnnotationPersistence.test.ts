import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useAnnotationPersistence } from "./useAnnotationPersistence";
import { AnnotationState } from "@/lib/annotation-queues";
import { Trace } from "@/types/traces";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

const mockCreateTraceComment = vi.fn();
const mockUpdateTraceComment = vi.fn();
const mockDeleteTraceComments = vi.fn();
const mockSetTraceFeedbackScore = vi.fn();
const mockDeleteTraceFeedbackScore = vi.fn();

vi.mock("@/api/traces/useCreateTraceCommentMutation", () => ({
  default: () => ({ mutate: mockCreateTraceComment }),
}));
vi.mock("@/api/traces/useCreateThreadCommentMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/traces/useUpdateTraceCommentMutation", () => ({
  default: () => ({ mutate: mockUpdateTraceComment }),
}));
vi.mock("@/api/traces/useUpdateThreadCommentMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/traces/useTraceCommentsBatchDeleteMutation", () => ({
  default: () => ({ mutate: mockDeleteTraceComments }),
}));
vi.mock("@/api/traces/useThreadCommentsBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/traces/useTraceFeedbackScoreSetMutation", () => ({
  default: () => ({ mutate: mockSetTraceFeedbackScore }),
}));
vi.mock("@/api/traces/useThreadFeedbackScoreSetMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/traces/useTraceFeedbackScoreDeleteMutation", () => ({
  default: () => ({ mutate: mockDeleteTraceFeedbackScore }),
}));
vi.mock("@/api/traces/useThreadFeedbackScoreDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

const makeTrace = (id = "trace-1"): Trace =>
  ({ id, project_id: "proj-1" }) as Trace;

const makeAnnotationQueue = () => ({
  id: "queue-1",
  project_id: "proj-1",
  project_name: "Test",
  name: "Queue",
  scope: ANNOTATION_QUEUE_SCOPE.TRACE,
  feedback_definition_names: [],
  items_count: 0,
  created_at: "",
  created_by: "",
  last_updated_at: "",
  last_updated_by: "",
  last_scored_at: "",
  comments_enabled: true,
});

const makeScore = (name: string, value: number) => ({
  name,
  value,
  source: FEEDBACK_SCORE_TYPE.ui,
  created_by: "user",
  last_updated_by: "user",
  last_updated_at: new Date().toISOString(),
});

const setup = (stateOverride?: Partial<AnnotationState>, item?: Trace) => {
  const state: AnnotationState = {
    scores: [],
    ...stateOverride,
  };
  const annotationStateRef = { current: state };
  const currentItemRef = { current: item || makeTrace() };

  const { result } = renderHook(() =>
    useAnnotationPersistence({
      annotationQueue: makeAnnotationQueue(),
      annotationStateRef:
        annotationStateRef as React.MutableRefObject<AnnotationState>,
      currentItemRef: currentItemRef as React.MutableRefObject<
        Trace | undefined
      >,
    }),
  );

  return { result, annotationStateRef, currentItemRef };
};

describe("useAnnotationPersistence", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("comment operations", () => {
    it("should CREATE comment when text is added on a fresh item", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledWith(
        { traceId: "trace-1", text: "hello", sourceQueueId: "queue-1" },
        expect.objectContaining({ onSuccess: expect.any(Function) }),
      );
    });

    it("should UPDATE comment when text changes and ID exists", () => {
      const { result } = setup({ comment: { text: "updated" } });

      act(() =>
        result.current.resetLastSaved(
          { id: "comment-1", text: "original" },
          [],
        ),
      );
      act(() => result.current.flushPendingChanges());

      expect(mockUpdateTraceComment).toHaveBeenCalledWith({
        commentId: "comment-1",
        traceId: "trace-1",
        text: "updated",
      });
    });

    it("should DELETE comment when text is cleared and ID exists", () => {
      const { result } = setup({ comment: { text: "" } });

      act(() =>
        result.current.resetLastSaved(
          { id: "comment-1", text: "original" },
          [],
        ),
      );
      act(() => result.current.flushPendingChanges());

      expect(mockDeleteTraceComments).toHaveBeenCalledWith({
        ids: ["comment-1"],
        traceId: "trace-1",
      });
    });

    it("should not save when text has not changed", () => {
      const { result } = setup({ comment: { text: "same" } });

      act(() =>
        result.current.resetLastSaved({ id: "comment-1", text: "same" }, []),
      );
      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).not.toHaveBeenCalled();
      expect(mockUpdateTraceComment).not.toHaveBeenCalled();
      expect(mockDeleteTraceComments).not.toHaveBeenCalled();
    });

    it("should block duplicate CREATEs while one is in-flight", () => {
      const { result, annotationStateRef } = setup({
        comment: { text: "first" },
      });

      act(() => result.current.flushPendingChanges());
      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);

      annotationStateRef.current = {
        scores: [],
        comment: { text: "second" },
      };
      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);
    });

    it("should retry after CREATE response arrives with new edits", () => {
      const { result, annotationStateRef } = setup({
        comment: { text: "first" },
      });

      act(() => result.current.flushPendingChanges());

      const onSuccess = mockCreateTraceComment.mock.calls[0][1].onSuccess;

      annotationStateRef.current = {
        scores: [],
        comment: { text: "edited" },
      };

      act(() => onSuccess({ id: "new-id" }));
      vi.advanceTimersByTime(1000);

      expect(mockUpdateTraceComment).toHaveBeenCalledWith(
        expect.objectContaining({
          commentId: "new-id",
          text: "edited",
        }),
      );
    });

    it("should CREATE after delete then re-type", () => {
      const { result, annotationStateRef } = setup({
        comment: { text: "" },
      });

      act(() =>
        result.current.resetLastSaved(
          { id: "comment-1", text: "original" },
          [],
        ),
      );
      act(() => result.current.flushPendingChanges());
      expect(mockDeleteTraceComments).toHaveBeenCalledTimes(1);

      annotationStateRef.current = {
        scores: [],
        comment: { text: "new text" },
      };
      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledWith(
        { traceId: "trace-1", text: "new text", sourceQueueId: "queue-1" },
        expect.any(Object),
      );
    });
  });

  describe("stale CREATE response after item switch", () => {
    it("should ignore CREATE response from previous item", () => {
      const { result, annotationStateRef, currentItemRef } = setup({
        comment: { text: "item1 comment" },
      });

      act(() => result.current.flushPendingChanges());
      const onSuccess = mockCreateTraceComment.mock.calls[0][1].onSuccess;

      annotationStateRef.current = { scores: [], comment: { text: "" } };
      currentItemRef.current = makeTrace("trace-2");
      act(() => result.current.resetLastSaved(undefined, []));

      act(() => onSuccess({ id: "stale-id" }));

      annotationStateRef.current = {
        scores: [],
        comment: { text: "item2 comment" },
      };
      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(2);
      expect(mockCreateTraceComment).toHaveBeenLastCalledWith(
        { traceId: "trace-2", text: "item2 comment", sourceQueueId: "queue-1" },
        expect.any(Object),
      );
      expect(mockUpdateTraceComment).not.toHaveBeenCalled();
    });
  });

  describe("score operations", () => {
    it("should SET changed scores", () => {
      const { result } = setup({
        scores: [makeScore("accuracy", 0.8)],
      });

      act(() => result.current.flushPendingChanges());

      expect(mockSetTraceFeedbackScore).toHaveBeenCalledWith(
        expect.objectContaining({
          traceId: "trace-1",
          name: "accuracy",
          value: 0.8,
          sourceQueueId: "queue-1",
        }),
      );
    });

    it("should DELETE removed scores", () => {
      const { result } = setup({ scores: [] });

      act(() =>
        result.current.resetLastSaved(undefined, [makeScore("accuracy", 0.5)]),
      );
      act(() => result.current.flushPendingChanges());

      expect(mockDeleteTraceFeedbackScore).toHaveBeenCalledWith({
        traceId: "trace-1",
        name: "accuracy",
      });
    });

    it("should not save when scores have not changed", () => {
      const scores = [makeScore("accuracy", 0.5)];
      const { result } = setup({ scores });

      act(() => result.current.resetLastSaved(undefined, scores));
      act(() => result.current.flushPendingChanges());

      expect(mockSetTraceFeedbackScore).not.toHaveBeenCalled();
      expect(mockDeleteTraceFeedbackScore).not.toHaveBeenCalled();
    });
  });

  describe("scheduleSave debounce", () => {
    it("should debounce saves by 1 second", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.scheduleSave());

      expect(mockCreateTraceComment).not.toHaveBeenCalled();

      act(() => vi.advanceTimersByTime(1000));

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);
    });

    it("should reset debounce timer on repeated calls", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.scheduleSave());
      act(() => vi.advanceTimersByTime(500));

      act(() => result.current.scheduleSave());
      act(() => vi.advanceTimersByTime(500));

      expect(mockCreateTraceComment).not.toHaveBeenCalled();

      act(() => vi.advanceTimersByTime(500));

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);
    });
  });

  describe("flushPendingChanges", () => {
    it("should cancel pending timer and save immediately", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.scheduleSave());

      expect(mockCreateTraceComment).not.toHaveBeenCalled();

      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);
    });
  });

  describe("resetLastSaved", () => {
    it("should cancel pending timer", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.scheduleSave());
      act(() => result.current.resetLastSaved(undefined, []));
      act(() => vi.advanceTimersByTime(2000));

      expect(mockCreateTraceComment).not.toHaveBeenCalled();
    });

    it("should set ignore flag when create is in-flight", () => {
      const { result, annotationStateRef, currentItemRef } = setup({
        comment: { text: "hello" },
      });

      act(() => result.current.flushPendingChanges());
      expect(mockCreateTraceComment).toHaveBeenCalledTimes(1);

      currentItemRef.current = makeTrace("trace-2");
      annotationStateRef.current = { scores: [] };
      act(() => result.current.resetLastSaved(undefined, []));

      const onSuccess = mockCreateTraceComment.mock.calls[0][1].onSuccess;
      act(() => onSuccess({ id: "stale-id" }));

      annotationStateRef.current = {
        scores: [],
        comment: { text: "new" },
      };
      act(() => result.current.flushPendingChanges());

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(2);
      expect(mockCreateTraceComment).toHaveBeenLastCalledWith(
        { traceId: "trace-2", text: "new", sourceQueueId: "queue-1" },
        expect.any(Object),
      );
    });
  });

  describe("error handling", () => {
    it("should reset lastSaved text on create error so retry works", () => {
      const { result } = setup({ comment: { text: "hello" } });

      act(() => result.current.flushPendingChanges());
      const onError = mockCreateTraceComment.mock.calls[0][1].onError;

      act(() => onError());
      vi.advanceTimersByTime(1000);

      expect(mockCreateTraceComment).toHaveBeenCalledTimes(2);
    });
  });
});
