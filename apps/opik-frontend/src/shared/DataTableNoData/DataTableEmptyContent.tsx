import React from "react";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import defaultLightImageUrl from "/images/empty-table-light.svg";
import defaultDarkImageUrl from "/images/empty-table-dark.svg";

type DataTableEmptyContentProps = {
  title: string;
  description: string;
  lightImageUrl?: string;
  darkImageUrl?: string;
  children?: React.ReactNode;
};

const DataTableEmptyContent: React.FC<DataTableEmptyContentProps> = ({
  title,
  description,
  lightImageUrl = defaultLightImageUrl,
  darkImageUrl = defaultDarkImageUrl,
  children,
}) => {
  const { themeMode } = useTheme();
  const imageUrl = themeMode === THEME_MODE.DARK ? darkImageUrl : lightImageUrl;

  return (
    <div className="sticky left-0 flex min-h-[50vh] w-[var(--scroll-body-client-width,100%)] items-center justify-center">
      <div className="flex flex-col items-center gap-2">
        <img src={imageUrl} alt="No data available" />
        <div className="flex flex-col items-center gap-2">
          <h3 className="comet-body-accented text-foreground">{title}</h3>
          <p className="comet-body-s max-w-[570px] text-center text-muted-slate">
            {description}
          </p>
        </div>
        {children}
      </div>
    </div>
  );
};

export default DataTableEmptyContent;
