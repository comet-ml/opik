import figma from "@figma/code-connect";

import { TableCell, TableRow } from "./table";

figma.connect(
  TableCell,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=50-957",
  {
    props: {
      content: figma.textContent("Label"),
    },
    example: ({ content }) => <TableCell>{content}</TableCell>,
  },
);

figma.connect(
  TableRow,
  "https://www.figma.com/design/DQkbgEBm59YiQUzoxxZ0ON/Opik-Design-System?node-id=376-20937",
  {
    props: {
      cells: figma.children("Cell*"),
    },
    example: ({ cells }) => <TableRow>{cells}</TableRow>,
  },
);
