import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import {
  FeatureTogglesProvider,
  useAreFeatureTogglesLoaded,
  useIsFeatureEnabled,
} from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys, FeatureToggles } from "@/types/feature-toggles";

let mockToggles: { data?: Partial<FeatureToggles>; isError: boolean };

vi.mock("@/api/feature-toggle/useFeatureToggle", () => ({
  default: () => mockToggles,
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "ws" }),
}));

type Snapshot = { loaded: boolean; ollie: boolean };

const renderProbe = () => {
  const snapshots: Snapshot[] = [];
  const Probe = () => {
    snapshots.push({
      loaded: useAreFeatureTogglesLoaded(),
      ollie: useIsFeatureEnabled(FeatureToggleKeys.OLLIE_ENABLED),
    });
    return null;
  };
  const tree = () => (
    <FeatureTogglesProvider>
      <Probe />
    </FeatureTogglesProvider>
  );
  const { rerender } = render(tree());
  return { snapshots, rerender: () => rerender(tree()) };
};

describe("FeatureTogglesProvider", () => {
  beforeEach(() => {
    mockToggles = { data: undefined, isError: false };
  });

  it("is not loaded while the toggles request is in flight", () => {
    const { snapshots } = renderProbe();

    expect(snapshots.at(-1)).toEqual({ loaded: false, ollie: false });
  });

  it("never reports loaded together with the default toggle values", () => {
    const { snapshots, rerender } = renderProbe();

    mockToggles = {
      data: { [FeatureToggleKeys.OLLIE_ENABLED]: true },
      isError: false,
    };
    rerender();

    expect(snapshots.at(-1)).toEqual({ loaded: true, ollie: true });
    expect(snapshots).not.toContainEqual({ loaded: true, ollie: false });
  });

  it("counts a failed toggles request as loaded with the defaults", () => {
    const { snapshots, rerender } = renderProbe();

    mockToggles = { data: undefined, isError: true };
    rerender();

    expect(snapshots.at(-1)).toEqual({ loaded: true, ollie: false });
  });
});
