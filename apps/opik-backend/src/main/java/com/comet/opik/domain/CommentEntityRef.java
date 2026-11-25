package com.comet.opik.domain;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record CommentEntityRef(UUID commentId, UUID entityId, CommentDAO.EntityType entityType, UUID projectId) {
}
