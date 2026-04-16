import React from "react";
import PairingStatusScreen from "@/shared/PairingStatusScreen/PairingStatusScreen";

const PairV1Page: React.FC = () => (
  <PairingStatusScreen status="error" errorKind="v1_workspace" />
);

export default PairV1Page;
