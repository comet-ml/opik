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

const OWLS = [Owl1, Owl2, Owl3, Owl4, Owl5, Owl6, Owl7, Owl8, Owl9, Owl10];

const SIZE_CLASS = {
  sm: "shrink-0 size-4",
  md: "shrink-0 size-7",
  lg: "shrink-0 size-8",
} as const;

interface ProjectIconProps {
  index: number;
  size?: keyof typeof SIZE_CLASS;
}

const ProjectIcon = ({ index, size = "sm" }: ProjectIconProps) => {
  const Icon = OWLS[index % PROJECT_ICON_COUNT];

  return <Icon className={SIZE_CLASS[size]} />;
};

export default ProjectIcon;
