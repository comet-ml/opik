import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import { applyArtifact } from "@/lib/ollieArtifacts";
import useAnnotatorViewArtifact, {
  ANNOTATOR_VIEW_ARTIFACT,
} from "./useAnnotatorViewArtifact";

const VIEW = "<script>kit.mount(() => kit.h('div'))</script>";
const SUMMARY = "Annotators see the answer first.";

const propose = (target: unknown, payload: unknown) =>
  applyArtifact({
    proposalId: "p1",
    kind: ANNOTATOR_VIEW_ARTIFACT,
    target,
    payload,
    summary: "Moved the answer up",
  });

const renderArtifact = (enabled = true) => {
  const onApply = vi.fn();
  const onReject = vi.fn();
  const hook = renderHook(() =>
    useAnnotatorViewArtifact({
      enabled,
      projectId: "pr1",
      queueId: "q1",
      scope: "thread",
      onApply,
      onReject,
    }),
  );
  return { onApply, onReject, hook };
};

const TARGET = { projectId: "pr1", queueId: "q1", scope: "thread" };
const PAYLOAD = { html: VIEW, kitVersion: 1, summary: SUMMARY };

describe("useAnnotatorViewArtifact", () => {
  it("applies a view and its summary for the open queue", () => {
    const { onApply, hook } = renderArtifact();

    expect(propose(TARGET, PAYLOAD)).toEqual({
      proposalId: "p1",
      applied: true,
    });
    expect(onApply).toHaveBeenCalledWith(VIEW, 1, SUMMARY);
    hook.unmount();
  });

  it("applies a replayed proposal only once", () => {
    const { onApply, hook } = renderArtifact();

    propose(TARGET, PAYLOAD);
    expect(propose(TARGET, PAYLOAD)).toEqual({
      proposalId: "p1",
      applied: true,
    });
    expect(onApply).toHaveBeenCalledTimes(1);
    hook.unmount();
  });

  it.each([
    ["another queue", { ...TARGET, queueId: "q2" }],
    ["another project", { ...TARGET, projectId: "pr2" }],
    ["another scope", { ...TARGET, scope: "trace" }],
  ])("rejects a view for %s", (_, target) => {
    const { onApply, onReject, hook } = renderArtifact();

    expect(propose(target, PAYLOAD)).toMatchObject({
      applied: false,
      reason: "target_mismatch",
    });
    expect(onApply).not.toHaveBeenCalled();
    expect(onReject).toHaveBeenCalledWith("target_mismatch");
    hook.unmount();
  });

  it.each([
    ["an empty view", { ...PAYLOAD, html: " " }],
    ["an unknown kit version", { ...PAYLOAD, kitVersion: 99 }],
    ["a missing kit version", { html: VIEW, summary: SUMMARY }],
    ["a missing summary", { html: VIEW, kitVersion: 1 }],
    ["an overlong summary", { ...PAYLOAD, summary: "a".repeat(1001) }],
    ["an oversized view", { ...PAYLOAD, html: "a".repeat(200_001) }],
  ])("rejects %s as invalid", (_, payload) => {
    const { onApply, hook } = renderArtifact();

    expect(propose(TARGET, payload)).toMatchObject({
      applied: false,
      reason: "invalid",
    });
    expect(onApply).not.toHaveBeenCalled();
    hook.unmount();
  });

  it("is not registered while disabled", () => {
    const { hook } = renderArtifact(false);

    expect(propose(TARGET, PAYLOAD)).toMatchObject({
      applied: false,
      reason: "no_handler",
    });
    hook.unmount();
  });
});
