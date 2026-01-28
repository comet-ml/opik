"use client";

import React, { type ReactNode } from "react";
import { motion, type Variants } from "framer-motion";

// ============================================================================
// ANIMATION VARIANTS
// ============================================================================

const nodeVariants: Variants = {
  hidden: {
    opacity: 0,
    y: 12,
    scale: 0.98,
  },
  visible: (index: number) => ({
    opacity: 1,
    y: 0,
    scale: 1,
    transition: {
      duration: 0.3,
      ease: "easeOut",
      delay: index * 0.05,
    },
  }),
};

// ============================================================================
// ANIMATED NODE COMPONENT
// ============================================================================

interface AnimatedNodeProps {
  /** Unique node identifier */
  nodeId: string;
  /** Child content to render */
  children: ReactNode;
  /** Whether this node is newly added during streaming */
  isNew: boolean;
  /** Whether the tree is currently streaming/loading */
  loading?: boolean;
  /** Index for staggered animation delay */
  index: number;
}

/**
 * Wraps node content with fade+slide animation during streaming.
 * Only animates nodes that are newly added while loading is true.
 * Once streaming completes (loading=false), nodes render without animation.
 */
export function AnimatedNode({
  nodeId,
  children,
  isNew,
  loading,
  index,
}: AnimatedNodeProps): JSX.Element {
  // Only animate if this is a new node and we're currently streaming
  if (!isNew || !loading) {
    return <>{children}</>;
  }

  return (
    <motion.div
      key={nodeId}
      custom={index}
      initial="hidden"
      animate="visible"
      variants={nodeVariants}
    >
      {children}
    </motion.div>
  );
}
