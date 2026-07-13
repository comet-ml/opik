import React from "react";

const Preview: React.FC = () => (
  <div className="flex h-screen items-center justify-center p-6">
    <p className="comet-body max-w-md text-center text-muted-foreground">
      Figma preview harness — overwrite src/dev-preview/Preview.tsx with the
      screen under construction (see .agents/skills/figma-screen).
    </p>
  </div>
);

export default Preview;
