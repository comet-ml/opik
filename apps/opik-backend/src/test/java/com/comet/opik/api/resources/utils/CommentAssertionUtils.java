package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Comment;
import lombok.experimental.UtilityClass;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class CommentAssertionUtils {
    public static final String[] IGNORED_FIELDS_COMMENTS = {"id", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};

    public static void assertComment(Comment expected, Comment actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_COMMENTS)
                .isEqualTo(expected);
        assertIgnoredFields(actual);
    }

    private static void assertIgnoredFields(Comment actual) {
        assertThat(actual.createdAt()).isNotNull();
        assertThat(actual.lastUpdatedAt()).isNotNull();
        assertThat(actual.createdBy()).isNotNull();
        assertThat(actual.lastUpdatedBy()).isNotNull();
    }

    public static void assertUpdatedComment(Comment initial, Comment updated, String expectedText) {
        assertThat(initial.text()).isNotEqualTo(expectedText);

        assertComment(initial.toBuilder().text(expectedText).build(), updated);

        assertThat(updated.createdAt()).isEqualTo(initial.createdAt());
        assertThat(updated.lastUpdatedAt()).isNotEqualTo(initial.lastUpdatedAt());
        assertThat(updated.createdBy()).isEqualTo(initial.createdBy());
        assertThat(updated.lastUpdatedBy()).isEqualTo(initial.lastUpdatedBy());
    }

    public static void assertComments(List<Comment> expected, List<Comment> actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_FIELDS_COMMENTS)
                .isEqualTo(expected);
        if (actual != null) {
            for (var actualComment : actual) {
                assertIgnoredFields(actualComment);
            }
        }
    }
}
