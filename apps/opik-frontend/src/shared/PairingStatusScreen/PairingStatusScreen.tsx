import React from "react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import opikLogoUrl from "/images/opik-logo.png";
import opikLogoInvertedUrl from "/images/opik-logo-inverted.png";

export type PairingStatus = "loading" | "success" | "error";
export type RunnerVariant = "connect" | "endpoint";
export type PairingErrorKind =
  | "invalid_link"
  | "tampered_link"
  | "expired_link"
  | "unreachable"
  | "insecure_context"
  | "v1_workspace";

export interface PairingStatusScreenProps {
  status: PairingStatus;
  runnerVariant?: RunnerVariant;
  errorKind?: PairingErrorKind;
}

function getCopy(props: PairingStatusScreenProps): {
  headline: string;
  subtitle: string;
} {
  if (props.status === "loading") {
    const headline =
      props.runnerVariant === "endpoint"
        ? "Connecting your agent"
        : props.runnerVariant === "connect"
          ? "Connecting to your codebase"
          : "Connecting";
    return {
      headline,
      subtitle: "Finalizing the pairing — this only takes a moment.",
    };
  }

  if (props.status === "success") {
    const headline =
      props.runnerVariant === "endpoint"
        ? "Your agent is connected to Opik"
        : "Opik is connected to your codebase";
    return {
      headline,
      subtitle: "Pairing successful — you can safely close this tab.",
    };
  }

  switch (props.errorKind) {
    case "tampered_link":
      return {
        headline: "This pairing link can't be trusted",
        subtitle:
          "The link appears to have been modified. Run the CLI command again.",
      };
    case "expired_link":
      return {
        headline: "This pairing link has expired",
        subtitle: "Run the CLI command again to generate a fresh link.",
      };
    case "unreachable":
      return {
        headline: "Couldn't reach Opik",
        subtitle: "Check your connection and try again.",
      };
    case "insecure_context":
      return {
        headline: "Secure connection required",
        subtitle: "Pairing requires HTTPS. Open the link on a secure page.",
      };
    case "v1_workspace":
      return {
        headline: "Workspace upgrade required",
        subtitle:
          "Opik Connect requires Opik 2.0. Please upgrade your workspace to continue.",
      };
    default:
      return {
        headline: "This pairing link is invalid",
        subtitle: "Run the CLI command again to generate a fresh link.",
      };
  }
}

export const PairingStatusScreen: React.FC<PairingStatusScreenProps> = (
  props,
) => {
  const { headline, subtitle } = getCopy(props);
  const { themeMode } = useTheme();

  return (
    <main
      aria-label="Pairing status"
      className="flex min-h-screen flex-col items-center justify-center p-6"
    >
      <img
        src={themeMode === THEME_MODE.DARK ? opikLogoInvertedUrl : opikLogoUrl}
        alt="Opik"
        className="mb-10 h-10"
      />
      <div className="flex flex-col items-center gap-2">
        <h1 className="comet-title-s text-center">{headline}</h1>
        <p className="comet-body text-center text-muted-slate">{subtitle}</p>
      </div>
    </main>
  );
};

export default PairingStatusScreen;
