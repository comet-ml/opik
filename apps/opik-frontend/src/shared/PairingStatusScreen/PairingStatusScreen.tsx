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
  // Workspace the CLI generated the pairing link for (from `?workspace=`).
  expectedWorkspace?: string | null;
  // Project name the CLI was pairing into (from `?project=`).
  expectedProject?: string | null;
  // Opik API base URL the CLI was talking to (from `?url=`). Used for
  // diagnostic display only — does not affect activation.
  expectedBaseUrl?: string | null;
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

// `expectedBaseUrl` is supplied by the CLI via `?url=` and goes straight
// into <a href>. Allow only http/https so a crafted pairing link (e.g.
// `?url=javascript:alert(1)`) can't produce a clickable script URL.
function safeHttpHref(raw: string | null | undefined): string | null {
  if (!raw) return null;
  try {
    const parsed = new URL(raw);
    return parsed.protocol === "https:" || parsed.protocol === "http:"
      ? raw
      : null;
  } catch {
    return null;
  }
}

export const PairingStatusScreen: React.FC<PairingStatusScreenProps> = (
  props,
) => {
  const { headline, subtitle } = getCopy(props);
  const { themeMode } = useTheme();

  const showWorkspaceContext =
    props.status === "error" &&
    !!(
      props.expectedWorkspace ||
      props.expectedProject ||
      props.expectedBaseUrl
    );
  const safeBaseUrlHref = safeHttpHref(props.expectedBaseUrl);

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
        {showWorkspaceContext && (
          <div className="mt-6 flex flex-col items-center gap-2">
            <p className="comet-body-s text-muted-slate">
              The CLI tried to pair using these Opik settings:
            </p>
            <dl
              aria-label="Pairing context"
              className="comet-body-s grid grid-cols-[auto_1fr] items-center gap-x-6 gap-y-1.5 rounded-md border border-border bg-soft-background px-5 py-3 text-left"
            >
              {props.expectedBaseUrl ? (
                <>
                  <dt className="text-muted-slate">Opik URL</dt>
                  <dd className="break-all font-medium">
                    {safeBaseUrlHref ? (
                      <a
                        href={safeBaseUrlHref}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-primary underline-offset-2 hover:underline"
                      >
                        {props.expectedBaseUrl}
                      </a>
                    ) : (
                      // Unsafe / non-http(s) scheme — show as plain text so
                      // the user can still see what the CLI tried, without
                      // a clickable script URL.
                      <span>{props.expectedBaseUrl}</span>
                    )}
                  </dd>
                </>
              ) : null}
              {props.expectedWorkspace ? (
                <>
                  <dt className="text-muted-slate">Workspace</dt>
                  <dd className="font-medium">{props.expectedWorkspace}</dd>
                </>
              ) : null}
              {props.expectedProject ? (
                <>
                  <dt className="text-muted-slate">Project</dt>
                  <dd className="break-all font-medium">
                    {props.expectedProject}
                  </dd>
                </>
              ) : null}
            </dl>
          </div>
        )}
      </div>
    </main>
  );
};

export default PairingStatusScreen;
