package com.comet.opik.domain.ollie;

import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.OllieStateConfig;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OllieStateServiceTest {

    private static final String USER_NAME = "testuser@example.com";
    private static final byte[] GZIP_HEADER = {(byte) 0x1f, (byte) 0x8b};

    @Mock
    private FileService fileService;

    private OllieStateServiceImpl service;

    @BeforeEach
    void setUp() {
        OllieStateConfig ollieStateConfig = new OllieStateConfig();
        ollieStateConfig.setMaxUploadSizeBytes(1024);

        service = new OllieStateServiceImpl(fileService, ollieStateConfig);
    }

    private static byte[] gzipData(int size) {
        byte[] data = new byte[size];
        data[0] = GZIP_HEADER[0];
        data[1] = GZIP_HEADER[1];
        return data;
    }

    private static String expectedKey(String userName) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(userName.getBytes(StandardCharsets.UTF_8));
        String userHash = HexFormat.of().formatHex(hash);
        return "ollie-state/%s/ollie.db.gz".formatted(userHash);
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("uploads valid gzip data to correct key via FileService")
        void uploadsToCorrectKey() throws Exception {
            byte[] data = gzipData(100);
            InputStream input = new ByteArrayInputStream(data);

            service.upload(USER_NAME, input);

            verify(fileService).upload(eq(expectedKey(USER_NAME)), any(byte[].class), eq("application/gzip"));
        }

        @Test
        @DisplayName("rejects non-gzip data")
        void rejectsNonGzip() {
            byte[] data = "not gzip".getBytes(StandardCharsets.UTF_8);
            InputStream input = new ByteArrayInputStream(data);

            assertThatThrownBy(() -> service.upload(USER_NAME, input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not gzip-compressed");
        }

        @Test
        @DisplayName("rejects data exceeding size limit")
        void rejectsOversizedUpload() {
            byte[] data = gzipData(1025);
            InputStream input = new ByteArrayInputStream(data);

            assertThatThrownBy(() -> service.upload(USER_NAME, input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("exceeds maximum size");
        }

        @Test
        @DisplayName("accepts data at exactly the size limit")
        void acceptsExactSizeLimit() throws Exception {
            byte[] data = gzipData(1024);
            InputStream input = new ByteArrayInputStream(data);

            service.upload(USER_NAME, input);

            verify(fileService).upload(any(String.class), any(byte[].class), any(String.class));
        }

        @Test
        @DisplayName("rejects empty data")
        void rejectsEmptyData() {
            InputStream input = new ByteArrayInputStream(new byte[0]);

            assertThatThrownBy(() -> service.upload(USER_NAME, input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not gzip-compressed");
        }

        @Test
        @DisplayName("rejects single byte data")
        void rejectsSingleByte() {
            InputStream input = new ByteArrayInputStream(new byte[]{(byte) 0x1f});

            assertThatThrownBy(() -> service.upload(USER_NAME, input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not gzip-compressed");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "\n"})
        @DisplayName("rejects blank usernames")
        void rejectsBlankUserName(String userName) {
            byte[] data = gzipData(10);
            InputStream input = new ByteArrayInputStream(data);

            assertThatThrownBy(() -> service.upload(userName, input))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("userName must not be blank");
        }
    }

    @Nested
    @DisplayName("download")
    class Download {

        @Test
        @DisplayName("returns stream from FileService for existing state")
        void returnsStream() throws Exception {
            InputStream responseStream = mock(InputStream.class);
            when(fileService.download(expectedKey(USER_NAME))).thenReturn(responseStream);

            InputStream result = service.download(USER_NAME);

            assertThat(result).isSameAs(responseStream);
            verify(fileService).download(expectedKey(USER_NAME));
        }

        @Test
        @DisplayName("throws NotFoundException when no state exists")
        void throwsNotFoundForMissing() throws Exception {
            when(fileService.download(expectedKey(USER_NAME)))
                    .thenThrow(new NotFoundException("File not found for key: " + expectedKey(USER_NAME)));

            assertThatThrownBy(() -> service.download(USER_NAME))
                    .isInstanceOf(NotFoundException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " "})
        @DisplayName("rejects blank usernames")
        void rejectsBlankUserName(String userName) {
            assertThatThrownBy(() -> service.download(userName))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("userName must not be blank");
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("deletes correct key via FileService")
        void deletesCorrectKey() throws Exception {
            service.delete(USER_NAME);

            verify(fileService).deleteObjects(Set.of(expectedKey(USER_NAME)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " "})
        @DisplayName("rejects blank usernames")
        void rejectsBlankUserName(String userName) {
            assertThatThrownBy(() -> service.delete(userName))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("userName must not be blank");
        }
    }

    @Nested
    @DisplayName("S3 key construction")
    class KeyConstruction {

        @Test
        @DisplayName("different usernames produce different keys")
        void differentUsersGetDifferentKeys() throws Exception {
            byte[] data = gzipData(10);

            service.upload("user-a", new ByteArrayInputStream(data));
            service.upload("user-b", new ByteArrayInputStream(data));

            verify(fileService).upload(eq(expectedKey("user-a")), any(byte[].class), any(String.class));
            verify(fileService).upload(eq(expectedKey("user-b")), any(byte[].class), any(String.class));
        }

        @Test
        @DisplayName("same username always produces same key")
        void sameUserGetsSameKey() throws Exception {
            byte[] data = gzipData(10);

            service.upload(USER_NAME, new ByteArrayInputStream(data));
            service.upload(USER_NAME, new ByteArrayInputStream(data));

            verify(fileService, times(2)).upload(eq(expectedKey(USER_NAME)), any(byte[].class), any(String.class));
        }
    }
}
