package com.ssafy.eyesonu.common.logging;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Logs timing for explicitly opted-in Spring bean methods without serializing arguments or results. */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class ExecutionLoggingAspect {

	private static final Logger log = LoggerFactory.getLogger(ExecutionLoggingAspect.class);

	@Around("@annotation(logExecution)")
	public Object logExecution(ProceedingJoinPoint joinPoint, LogExecution logExecution) throws Throwable {
		long startedAt = System.nanoTime();
		String method = methodName(joinPoint);
		try {
			Object result = joinPoint.proceed();
			log.info(
					"method_execution method={} status=SUCCESS elapsedMs={}",
					method,
					elapsedMillis(startedAt));
			return result;
		}
		catch (Throwable exception) {
			// The service or GlobalExceptionHandler owns the full exception stack trace.
			// This timing log intentionally records only the exception type to avoid duplicates.
			log.warn(
					"method_execution method={} status=FAILURE elapsedMs={} exceptionType={}",
					method,
					elapsedMillis(startedAt),
					exception.getClass().getName());
			throw exception;
		}
	}

	private String methodName(ProceedingJoinPoint joinPoint) {
		MethodSignature signature = (MethodSignature) joinPoint.getSignature();
		return signature.getDeclaringTypeName() + "." + signature.getName();
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}
}
