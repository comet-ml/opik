package com.comet.opik.domain;

import com.comet.opik.api.Comment;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.r2dbc.spi.Result;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;

@Slf4j
@UtilityClass
public class CommentResultMapper {

    private static final TypeReference<List<Comment>> COMMENT_LIST_TYPE = new TypeReference<>() {
    };

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
        if (commentsRaw instanceof String json) {
            return parseCommentsFromJson(json);
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

    public static List<Comment> parseCommentsFromJson(String json) {
        if (StringUtils.isBlank(json) || "[]".equals(json)) {
            return null;
        }

        List<Comment> comments = JsonUtils.readValue(json, COMMENT_LIST_TYPE);
        return CollectionUtils.isEmpty(comments)
                ? null
                : comments
                        .stream()
                        .sorted(Comparator.comparing(Comment::id))
                        .toList();
    }
}
