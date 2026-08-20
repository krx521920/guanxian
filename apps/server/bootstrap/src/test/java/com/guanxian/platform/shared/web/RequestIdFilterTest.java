package com.guanxian.platform.shared.web;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesRequestIdOnlyDuringRequestAndRemovesItAfterward() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "request-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertEquals("request-123", MDC.get(RequestIdFilter.MDC_KEY)));

        assertEquals("request-123", response.getHeader(RequestIdFilter.HEADER_NAME));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void restoresOuterMdcValueWhenDownstreamThrows() {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-operation");
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "request-that-fails");
        var response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertEquals("request-that-fails", MDC.get(RequestIdFilter.MDC_KEY));
            throw new ServletException("downstream failure");
        }));

        assertEquals("request-that-fails", response.getHeader(RequestIdFilter.HEADER_NAME));
        assertEquals("outer-operation", MDC.get(RequestIdFilter.MDC_KEY));
    }
}
