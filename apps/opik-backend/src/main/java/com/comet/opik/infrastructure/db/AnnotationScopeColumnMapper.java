package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AnnotationQueue;

public class AnnotationScopeColumnMapper extends AbstractEnumColumnMapper<AnnotationQueue.AnnotationScope> {
    public AnnotationScopeColumnMapper() {
        super(AnnotationQueue.AnnotationScope::fromString, "annotation_scope");
    }
}
