package com.comet.opik.domain;

import com.comet.opik.api.Comment;
import io.r2dbc.spi.Result;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.reactivestreams.Publisher;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@UtilityClass
class CommentResultMapper {

    static Publisher<Comment> mapItem(Result results) {
        return results.map((row, rowMetadata) -> Comment.builder()
                .id(row.get("id", UUID.class))
                .text(row.get("text", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }

    static List<Comment> getComments(Object commentsRaw) {
        if (commentsRaw instanceof List[] commentsArray) {
            return getComments(commentsArray);
        }
        return null;
    }

    static List<Comment> getComments(List[] commentsArrays) {
        if (ArrayUtils.isEmpty(commentsArrays)) {
            return null;
        }

        var commentItems = Arrays.stream(commentsArrays)
                .filter(commentItem -> CollectionUtils.isNotEmpty(commentItem) &&
                        !CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(commentItem.get(0).toString()))
                .map(commentItem -> Comment.builder()
                        .id(UUID.fromString(commentItem.get(0).toString()))
                        .text(commentItem.get(1).toString())
                        .createdAt(Instant.parse(commentItem.get(2).toString()))
                        .lastUpdatedAt(Instant.parse(commentItem.get(3).toString()))
                        .createdBy(commentItem.get(4).toString())
                        .lastUpdatedBy(commentItem.get(5).toString())
                        .build())
                .sorted(Comparator.comparing(Comment::id))
                .toList();

        return commentItems.isEmpty() ? null : commentItems;
    }
}
