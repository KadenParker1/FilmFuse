package com.pm.filmfuse.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pm.filmfuse.common.exception.ErrorResponseDTO;
import com.pm.filmfuse.common.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Bodies without a Content-Length (chunked), which MockMvc cannot send. */
class RequestBodyLimitFilterTest {

    /** A request whose length is unknown until it is read. */
    private static MockHttpServletRequest chunked(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reports") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent("y".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void aChunkedBodyOverTheCapFailsWhileBeingRead() {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(1024);
        assertThatThrownBy(() -> filter.doFilter(chunked(5000), new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void aChunkedBodyUnderTheCapIsReadWhole() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(1024);
        AtomicReference<String> read = new AtomicReference<>();
        filter.doFilter(chunked(1024), new MockHttpServletResponse(),
                (req, res) -> read.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
        assertThat(read.get()).hasSize(1024);
    }

    @Test
    void theReaderIsLimitedToo() {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(10);
        assertThatThrownBy(() -> filter.doFilter(chunked(100), new MockHttpServletResponse(),
                (req, res) -> req.getReader().readLine()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void aDeclaredLengthOverTheCapIsRefusedWithoutCallingTheChain() throws Exception {
        RequestBodyLimitFilter filter = new RequestBodyLimitFilter(10);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reports");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("must not be called");
        });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"The request body is too large.\"}");
    }

    @Test
    void theExceptionHandlerTurnsAWrappedOverflowInto413() throws Exception {
        HttpMessageNotReadableException wrapped = new HttpMessageNotReadableException("JSON parse error",
                new RuntimeException(new PayloadTooLargeException(10)), new MockHttpInputMessage(new byte[0]));
        ResponseEntity<ErrorResponseDTO> response = new GlobalExceptionHandler().handleUnreadable(wrapped);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().message()).isEqualTo("The request body is too large.");

        ResponseEntity<ErrorResponseDTO> plain = new GlobalExceptionHandler().handleUnreadable(
                new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])));
        assertThat(plain.getStatusCode().value()).isEqualTo(400);
    }
}
