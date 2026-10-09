import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { useForm } from "react-hook-form";
import { Form } from "@/ui/form";
import { ALERT_TYPE } from "@/types/alerts";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import WebhookSettings from "./WebhookSettings";
import { AlertFormType } from "./schema";

const mockIsFeatureEnabled = vi.fn();

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: (feature: FeatureToggleKeys) =>
    mockIsFeatureEnabled(feature),
}));

const Harness: React.FC = () => {
  const form = useForm<AlertFormType>({
    defaultValues: {
      name: "",
      enabled: true,
      alertType: ALERT_TYPE.general,
      routingKey: "",
      url: "",
      secretToken: "",
      headers: [],
      triggers: [],
    },
  });

  return (
    <Form {...form}>
      <WebhookSettings
        form={form}
        onTestConnection={() => undefined}
        isTestPending={false}
        isPending={false}
      />
    </Form>
  );
};

const NOTE =
  "Triggered alerts are also published to the deployment's AWS EventBridge bus.";

describe("WebhookSettings", () => {
  beforeEach(() => {
    mockIsFeatureEnabled.mockReset();
  });

  it("shows the EventBridge note when the toggle is on", () => {
    mockIsFeatureEnabled.mockImplementation(
      (feature: FeatureToggleKeys) =>
        feature === FeatureToggleKeys.EVENT_BRIDGE_ALERTS_ENABLED,
    );

    render(<Harness />);

    expect(screen.getByText(NOTE)).toBeInTheDocument();
    expect(screen.getByTestId("alert-webhook-url-input")).toBeInTheDocument();
  });

  it("hides the EventBridge note when the toggle is off", () => {
    mockIsFeatureEnabled.mockReturnValue(false);

    render(<Harness />);

    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
  });
});
