import React from "react";

interface LoadingOverlayProps {
  isVisible: boolean;
}

const LoadingOverlay: React.FC<LoadingOverlayProps> = ({ isVisible }) => {
  if (!isVisible) return null;

  return (
    <div className="absolute inset-0 z-20 animate-pulse bg-background/70 duration-[1500ms] ease-in-out" />
  );
};

export default LoadingOverlay;

