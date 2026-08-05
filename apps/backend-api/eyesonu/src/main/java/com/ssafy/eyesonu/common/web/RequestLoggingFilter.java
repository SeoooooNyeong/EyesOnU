package com.ssafy.eyesonu.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the request trace context and records one safe summary per HTTP request.
 *
 * <p>The filter deliberately does not inspect request bodies or headers other than the
 * request-id header. It is ordered before Spring Security so authentication and access
 * denial responses receive the same trace id and HTTP summary.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-ID";
	public static final String TRACE_ID_MDC_KEY = "traceId";
	private static final int MAX_REQUEST_ID_LENGTH = 128;
	private static final Pattern VALID_REQUEST_ID =
			Pattern.compile("[A-Za-z0-9._-]{1," + MAX_REQUEST_ID_LENGTH + "}");
	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String traceId = resolveTraceId(request.getHeader(REQUEST_ID_HEADER));
		long startedAt = System.nanoTime();
		MDC.put(TRACE_ID_MDC_KEY, traceId);
		response.setHeader(REQUEST_ID_HEADER, traceId);

		int statusCode = response.getStatus();
		try {
			filterChain.doFilter(request, response);
			statusCode = response.getStatus();
		}
		catch (IOException | ServletException | RuntimeException exception) {
			statusCode = response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST
					? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			throw exception;
		}
		catch (Error error) {
			statusCode = response.getStatus() >= HttpServletResponse.SC_BAD_REQUEST
					? response.getStatus() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			throw error;
		}
		finally {
			try {
				log.info(
						"http_request traceId={} method={} uri={} statusCode={} elapsedMs={}",
						traceId,
						request.getMethod(),
						request.getRequestURI(),
						statusCode,
						elapsedMillis(startedAt));
			}
			finally {
				MDC.remove(TRACE_ID_MDC_KEY);
			}
		}
	}

	static String resolveTraceId(String requestId) {
		return requestId != null && VALID_REQUEST_ID.matcher(requestId).matches()
				? requestId
				: UUID.randomUUID().toString();
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}
}
