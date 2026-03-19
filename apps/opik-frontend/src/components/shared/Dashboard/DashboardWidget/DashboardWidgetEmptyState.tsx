import React from "react";
import { Book, Inbox, Pencil } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

type EditActionProps = {
  label?: string;
  onClick: () => void;
};

const EditAction: React.FunctionComponent<EditActionProps> = ({
  label = "Configure",
  onClick,
}) => (
  <Button
    variant="link"
    size="sm"
    onClick={(e) => {
      e.stopPropagation();
      onClick();
    }}
    className="gap-1"
  >
    <Pencil className="size-3.5" />
    {label}
  </Button>
);

type DocsLinkProps = {
  label: string;
  href: string;
};

const DocsLink: React.FunctionComponent<DocsLinkProps> = ({ label, href }) => (
  <Button variant="link" size="sm" asChild className="gap-1">
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      onClick={(e) => e.stopPropagation()}
    >
      <Book className="size-3.5" />
      {label}
    </a>
  </Button>
);

type DashboardWidgetEmptyStateProps = {
  title?: string;
  message?: string;
  icon?: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
};

type DashboardWidgetEmptyStateComponent =
  React.FunctionComponent<DashboardWidgetEmptyStateProps> & {
    EditAction: typeof EditAction;
    DocsLink: typeof DocsLink;
  };

const DashboardWidgetEmptyState: DashboardWidgetEmptyStateComponent =
  Object.assign(
    ({
      title = "No data",
      message = "There is no data to display for this widget",
      icon,
      action,
      className,
    }: DashboardWidgetEmptyStateProps) => {
      return (
        <div
          className={cn(
            "flex h-full flex-col overflow-y-auto overflow-x-hidden px-4 py-2",
            className,
          )}
        >
          <div className="m-auto flex w-full flex-col items-center text-center">
            <div className="mb-3 text-muted-slate">
              {icon || <Inbox className="size-4" />}
            </div>
            <h3 className="comet-body-s-accented mb-1 text-foreground">
              {title}
            </h3>
            <p className="comet-body-s w-full break-words pb-1 text-muted-slate">
              {message}
            </p>
            {action}
          </div>
        </div>
      );
    },
    {
      EditAction,
      DocsLink,
    },
  );

export default DashboardWidgetEmptyState;
