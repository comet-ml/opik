import React from "react";

interface LoadingOverlayProps {
  isVisible: boolean;
}

const LoadingOverlay: React.FC<LoadingOverlayProps> = ({ isVisible }) => {
  if (!isVisible) return null;

  return (
    <div className="duration-[1500ms] absolute inset-0 z-20 animate-pulse bg-background/70 ease-in-out" />
  );
};

export default LoadingOverlay;
