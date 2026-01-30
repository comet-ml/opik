import React from "react";
import { useIsPhone } from "@/hooks/useIsPhone";
import CodeSectionTitle from "@/components/shared/CodeSectionTitle/CodeSectionTitle";
import CodeBlockWithHeader from "@/components/shared/CodeBlockWithHeader/CodeBlockWithHeader";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { PIP_INSTALL_OPIK_COMMAND } from "@/constants/shared";

type InstallOpikSectionProps = {
  title: string;
};

const InstallOpikSection: React.FC<InstallOpikSectionProps> = ({ title }) => {
  const { isPhonePortrait } = useIsPhone();

  return (
    <div>
      <CodeSectionTitle>{title}</CodeSectionTitle>
      {isPhonePortrait ? (
        <CodeBlockWithHeader
          title="Terminal"
          copyText={PIP_INSTALL_OPIK_COMMAND}
        >
          <CodeHighlighter data={PIP_INSTALL_OPIK_COMMAND} />
        </CodeBlockWithHeader>
      ) : (
        <div className="min-h-7">
          <CodeHighlighter data={PIP_INSTALL_OPIK_COMMAND} />
        </div>
      )}
    </div>
  );
};

export default InstallOpikSection;
