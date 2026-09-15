import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import useAppStore from "@/store/AppStore";
import { OpikEvent, trackEvent } from "./tracking";

const track = vi.fn();

describe("trackEvent", () => {
  beforeEach(() => {
    track.mockClear();
    window.analytics = { track } as unknown as typeof window.analytics;
  });

  afterEach(() => {
    useAppStore.setState({ activeWorkspaceName: "" });
  });

  // The Ollie service events key on `workspace`; without the same key here the two families cannot be
  // joined - OPIK-7991.
  it("sends the active workspace with every event", () => {
    useAppStore.setState({ activeWorkspaceName: "acme" });

    trackEvent(OpikEvent.DIAGNOSTICS_RUN_CLICKED, { project_id: "p1" });

    expect(track).toHaveBeenCalledWith(
      "opik_diagnostics_run_clicked",
      expect.objectContaining({ project_id: "p1", workspace: "acme" }),
    );
  });

  it("omits the workspace rather than sending an empty one", () => {
    trackEvent(OpikEvent.DIAGNOSTICS_RUN_CLICKED, { project_id: "p1" });

    expect(track).toHaveBeenCalledWith(
      "opik_diagnostics_run_clicked",
      expect.not.objectContaining({ workspace: expect.anything() }),
    );
  });
});
