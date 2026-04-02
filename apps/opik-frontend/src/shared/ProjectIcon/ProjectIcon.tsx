import { PROJECT_ICON_COUNT } from "@/constants/projectIcons";

import Owl1 from "@/icons/projects/owl-1.svg?react";
import Owl2 from "@/icons/projects/owl-2.svg?react";
import Owl3 from "@/icons/projects/owl-3.svg?react";
import Owl4 from "@/icons/projects/owl-4.svg?react";
import Owl5 from "@/icons/projects/owl-5.svg?react";
import Owl6 from "@/icons/projects/owl-6.svg?react";
import Owl7 from "@/icons/projects/owl-7.svg?react";
import Owl8 from "@/icons/projects/owl-8.svg?react";
import Owl9 from "@/icons/projects/owl-9.svg?react";
import Owl10 from "@/icons/projects/owl-10.svg?react";

import Robot1 from "@/icons/projects/robot-1.svg?react";
import Robot2 from "@/icons/projects/robot-2.svg?react";
import Robot3 from "@/icons/projects/robot-3.svg?react";
import Robot4 from "@/icons/projects/robot-4.svg?react";
import Robot5 from "@/icons/projects/robot-5.svg?react";
import Robot6 from "@/icons/projects/robot-6.svg?react";
import Robot7 from "@/icons/projects/robot-7.svg?react";
import Robot8 from "@/icons/projects/robot-8.svg?react";
import Robot9 from "@/icons/projects/robot-9.svg?react";
import Robot10 from "@/icons/projects/robot-10.svg?react";

const OWLS = [Owl1, Owl2, Owl3, Owl4, Owl5, Owl6, Owl7, Owl8, Owl9, Owl10];
const ROBOTS = [
  Robot1,
  Robot2,
  Robot3,
  Robot4,
  Robot5,
  Robot6,
  Robot7,
  Robot8,
  Robot9,
  Robot10,
];

interface ProjectIconProps {
  index: number;
  variant?: "owl" | "robot";
}

const ProjectIcon = ({ index, variant = "robot" }: ProjectIconProps) => {
  const safeIndex = index % PROJECT_ICON_COUNT;
  const Icon = variant === "owl" ? OWLS[safeIndex] : ROBOTS[safeIndex];

  return <Icon className={variant === "owl" ? "size-8" : "size-4"} />;
};

export default ProjectIcon;
