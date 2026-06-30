import { describe, it, expect } from "vitest";
import { renderHook } from "@testing-library/react";
import useExplainStore, { useCanExplain } from "./explainStore";
import { BRIDGE_PROTOCOL_VERSION } from "@/types/assistant-sidebar";

// useCanExplain is a pure zustand selector hook (no React context), so we just
// drive the store state and read the hook result.
const setGate = (
  ready: boolean,
  capabilities: string[],
  consoleBridgeVersion: number | null,
) =>
  useExplainStore.setState({
    ready,
    capabilities,
    consoleBridgeVersion,
    entries: {},
    routes: {},
    emit: () => {}, // bridge connected
  });

const canExplain = () => renderHook(() => useCanExplain()).result.current;

describe("useCanExplain gate", () => {
  it("is true when pod ready, 'explain' capability present, bridge matches", () => {
    setGate(true, ["explain"], BRIDGE_PROTOCOL_VERSION);
    expect(canExplain()).toBe(true);
  });

  it("is true when the console bridge is NEWER than ours (>=)", () => {
    setGate(true, ["explain"], BRIDGE_PROTOCOL_VERSION + 1);
    expect(canExplain()).toBe(true);
  });

  it("is false when the console bridge is OLDER than ours", () => {
    setGate(true, ["explain"], BRIDGE_PROTOCOL_VERSION - 1);
    expect(canExplain()).toBe(false);
  });

  it("is false before console:ready (bridge version still unknown)", () => {
    setGate(true, ["explain"], null);
    expect(canExplain()).toBe(false);
  });

  it("is false when the 'explain' capability is absent", () => {
    setGate(true, [], BRIDGE_PROTOCOL_VERSION);
    expect(canExplain()).toBe(false);
  });

  it("is false when the pod is not ready", () => {
    setGate(false, ["explain"], BRIDGE_PROTOCOL_VERSION);
    expect(canExplain()).toBe(false);
  });

  it("is false when no bridge emitter is connected", () => {
    setGate(true, ["explain"], BRIDGE_PROTOCOL_VERSION);
    useExplainStore.setState({ emit: null });
    expect(canExplain()).toBe(false);
  });
});
