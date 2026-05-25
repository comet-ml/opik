package com.comet.opik.utils;

import com.comet.opik.api.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceUtilsTest {

    public static Stream<Arguments> getProjectName() {
        return Stream.of(
                Arguments.of(null, DEFAULT_PROJECT),
                Arguments.of("", DEFAULT_PROJECT),
                Arguments.of("   ", DEFAULT_PROJECT),
                Arguments.of("\t\n", DEFAULT_PROJECT),
                Arguments.of("My Project", "My Project"),
                Arguments.of("My Project ", "My Project"),
                Arguments.of("  My Project", "My Project"),
                Arguments.of("  My Project  ", "My Project"));
    }

    @ParameterizedTest
    @MethodSource
    void getProjectName(String input, String expected) {
        assertEquals(expected, WorkspaceUtils.getProjectName(input));
    }

    public static Stream<Arguments> stripProjectName() {
        return Stream.of(
                Arguments.of("My Project", "My Project"),
                Arguments.of("My Project ", "My Project"),
                Arguments.of("  My Project", "My Project"),
                Arguments.of("  My Project  ", "My Project"),
                Arguments.of("\tMy Project\n", "My Project"));
    }

    @ParameterizedTest
    @MethodSource
    void stripProjectName(String storedName, String expected) {
        Project project = Project.builder().name(storedName).build();
        assertEquals(expected, WorkspaceUtils.stripProjectName(project));
    }

    @Test
    void stripProjectName__whenProjectIsNull__throwsNpe() {
        assertThrows(NullPointerException.class, () -> WorkspaceUtils.stripProjectName(null));
    }

}
