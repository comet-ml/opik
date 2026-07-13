import figma from "@figma/code-connect";

import { Tag } from "./tag";

figma.connect(
  Tag,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=3297-7035",
  {
    props: {
      variant: figma.enum("Color", {
        Purple: "purple",
        Burgundy: "burgundy",
        Pink: "pink",
        Red: "red",
        Orange: "orange",
        Yellow: "yellow",
        Green: "green",
        Turquoise: "turquoise",
        Blue: "blue",
        Gray: "gray",
        Custom: "default",
        LightGray: "default",
      }),
      iconLeft: figma.boolean("ShowIconLeft", {
        true: figma.instance("↳ IconLeft"),
        false: undefined,
      }),
      iconRight: figma.boolean("ShowIconRight", {
        true: figma.instance("↳ IconRight"),
        false: undefined,
      }),
      label: figma.textContent("Label"),
    },
    example: ({ variant, iconLeft, iconRight, label }) => (
      <Tag variant={variant}>
        {iconLeft}
        {label}
        {iconRight}
      </Tag>
    ),
  },
);
