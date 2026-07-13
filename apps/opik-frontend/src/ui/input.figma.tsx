import figma from "@figma/code-connect";

import { Input } from "./input";

figma.connect(
  Input,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=7-3826",
  {
    props: {
      dimension: figma.enum("Size", {
        Medium: "default",
        Small: "sm",
      }),
      disabled: figma.enum("State", {
        Disabled: true,
      }),
    },
    example: ({ dimension, disabled }) => (
      <Input dimension={dimension} disabled={disabled} />
    ),
  },
);
