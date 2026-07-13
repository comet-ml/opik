import figma from "@figma/code-connect";

import { SelectBox } from "./SelectBox";

figma.connect(
  SelectBox,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=7-3884",
  {
    props: {
      size: figma.enum("Size", { Medium: "default", Small: "sm" }),
    },
    example: ({ size }) => (
      <SelectBox size={size} value="" onChange={() => {}} options={[]} />
    ),
  },
);
