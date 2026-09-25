import { ArrowUpRight, Coins } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useUser from "@/plugins/comet/useUser";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import { buildUrl } from "@/plugins/comet/utils";

type BillingLinkVariant = "inline" | "action" | "popover";

const VARIANT_CLASS: Record<BillingLinkVariant, string> = {
  inline: "underline underline-offset-4 hover:text-primary",
  action:
    "inline-flex items-center gap-1 text-xs font-normal hover:text-primary-hover",
  popover:
    "comet-body-xs mt-1.5 flex h-6 items-center gap-1 rounded border-t border-border px-1 pt-1.5 text-foreground hover:bg-primary-foreground",
};

export type BillingLinkProps = {
  label?: string;
  variant?: BillingLinkVariant;
};

const BillingLink = ({
  label = "View billing",
  variant = "inline",
}: BillingLinkProps) => {
  const activeWorkspaceName = useActiveWorkspaceName();
  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === activeWorkspaceName,
  );

  if (!workspace?.organizationId) return null;

  // Ollie credits live in the Admin Dashboard, which only organization admins can open — a member
  // following this link is silently bounced back to Opik with no explanation. Better to not offer it.
  const organization = organizations?.find(
    (org) => org.id === workspace.organizationId,
  );
  if (organization?.role !== ORGANIZATION_ROLE_TYPE.admin) return null;

  const href = buildUrl(
    `organizations/${workspace.organizationId}/ollie-credits`,
    activeWorkspaceName,
  );

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={VARIANT_CLASS[variant]}
    >
      {variant === "action" && <Coins className="size-3" />}
      {label}
      {variant === "popover" && (
        <ArrowUpRight className="size-3 text-light-slate" />
      )}
    </a>
  );
};

export default BillingLink;
