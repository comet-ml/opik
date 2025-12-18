import React from "react";
import Loader from "@/components/shared/Loader/Loader";

interface LoadingOverlayProps {
  isVisible: boolean;
}

const LoadingOverlay: React.FC<LoadingOverlayProps> = ({ isVisible }) => {
  if (!isVisible) return null;

  return (
    <div className="pointer-events-none absolute inset-0 z-50 bg-background/30">
      <div className="sticky left-0 flex h-full w-screen max-w-full items-center justify-center">
        <Loader className="min-h-56" message="" />
      </div>
    </div>
  );
};

export default LoadingOverlay;

