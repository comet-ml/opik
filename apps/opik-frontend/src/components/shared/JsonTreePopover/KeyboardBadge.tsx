import React from "react";

interface KeyboardBadgeProps {
  children: React.ReactNode;
}

const KeyboardBadge: React.FC<KeyboardBadgeProps> = ({ children }) => (
  <kbd className="inline-flex items-center justify-center px-1.5 py-0.5 text-xs font-medium bg-muted border rounded">
    {children}
  </kbd>
);

export default KeyboardBadge;
