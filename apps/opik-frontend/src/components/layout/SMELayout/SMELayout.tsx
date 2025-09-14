import React from "react";
import { Outlet } from "@tanstack/react-router";

// Note: Using a simple div for progress bar since Progress component doesn't exist

type SMELayoutProps = {
  children?: React.ReactNode;
  progress?: {
    current: number;
    total: number;
  };
};

const SMELayout: React.FunctionComponent<SMELayoutProps> = ({
  children = <Outlet />,
  progress,
}) => {
  const progressPercentage = progress
    ? Math.round((progress.current / progress.total) * 100)
    : 0;

  return (
    <div className="min-h-screen bg-background">
      {/* Minimal header with Opik logo and progress */}
      <header className="border-b bg-white px-4 py-3 shadow-sm">
        <div className="mx-auto flex max-w-4xl items-center justify-between">
          <div className="flex items-center space-x-2">
            <div className="text-lg font-semibold text-primary">Opik</div>
            <div className="text-sm text-muted-foreground">
              Annotation Review
            </div>
          </div>

          {progress && (
            <div className="flex items-center space-x-3">
              <span className="text-sm text-muted-foreground">
                {progress.current} of {progress.total} completed
              </span>
              <div className="w-24 h-2 bg-gray-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-primary transition-all duration-300"
                  style={{ width: `${progressPercentage}%` }}
                  aria-label={`Progress: ${progressPercentage}%`}
                />
              </div>
            </div>
          )}
        </div>
      </header>

      {/* Main content area */}
      <main className="mx-auto max-w-4xl px-4 py-6">{children}</main>
    </div>
  );
};

export default SMELayout;
