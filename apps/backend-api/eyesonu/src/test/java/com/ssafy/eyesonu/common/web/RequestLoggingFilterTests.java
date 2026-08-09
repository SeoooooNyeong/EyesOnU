package com.ssafy.eyesonu.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTests {

	private final RequestLoggingFilter filter = new RequestLoggingFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void preservesValidRequestIdAndCleansMdcAfterRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/cases");
		request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "client-request-01");
		MockHttpServletResponse response = new MockHttpServletResponse();
		String[] traceIdSeenInChain = new String[1];
		FilterChain chain = (chainRequest, chainResponse) -> {
			traceIdSeenInChain[0] = MDC.get(RequestLoggingFilter.TRACE_ID_MDC_KEY);
			((HttpServletResponse) chainResponse).setStatus(204);
		};

		filter.doFilter(request, response, chain);

		assertEquals("client-request-01", traceIdSeenInChain[0]);
		assertEquals("client-request-01", response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER));
		assertNull(MDC.get(RequestLoggingFilter.TRACE_ID_MDC_KEY));
		assertEquals(204, response.getStatus());
	}

	@Test
	void generatesUuidForMissingOrInvalidRequestId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/admin/login");
		request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "invalid request id");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (chainRequest, chainResponse) -> { });

		String responseRequestId = response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
		assertTrue(responseRequestId != null && responseRequestId.matches(
				"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
		assertEquals(responseRequestId, RequestLoggingFilter.resolveTraceId(responseRequestId));
		assertDoesNotThrowUuidParse(responseRequestId);
		assertNull(MDC.get(RequestLoggingFilter.TRACE_ID_MDC_KEY));
	}

	@Test
	void cleansMdcWhenFilterChainThrows() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/failure");
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertThrows(ServletException.class, () -> filter.doFilter(
				request, response, (chainRequest, chainResponse) -> {
					throw new ServletException("test failure");
				}));

		assertNull(MDC.get(RequestLoggingFilter.TRACE_ID_MDC_KEY));
	}

	@Test
	void writesOneSafeSummaryWithoutHeadersOrBody() throws Exception {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/cases");
			request.addHeader("Authorization", "Bearer should-not-be-logged");
			request.setContent("password=should-not-be-logged".getBytes(StandardCharsets.UTF_8));
			MockHttpServletResponse response = new MockHttpServletResponse();

			filter.doFilter(request, response, (chainRequest, chainResponse) -> { });

			assertEquals(1, appender.list.size());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("http_request"));
			assertTrue(!message.contains("should-not-be-logged"));
			assertTrue(!message.contains("Authorization"));
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private void assertDoesNotThrowUuidParse(String value) {
		UUID.fromString(value);
	}
}
