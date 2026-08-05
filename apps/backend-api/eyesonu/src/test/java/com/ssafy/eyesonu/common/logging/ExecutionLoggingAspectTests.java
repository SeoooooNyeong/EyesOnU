package com.ssafy.eyesonu.common.logging;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

class ExecutionLoggingAspectTests {

	private ExecutionLoggingAspect aspect;
	private ProceedingJoinPoint joinPoint;
	private LogExecution annotation;
	private MethodSignature signature;

	@BeforeEach
	void setUp() {
		aspect = new ExecutionLoggingAspect();
		joinPoint = mock(ProceedingJoinPoint.class);
		annotation = mock(LogExecution.class);
		signature = mock(MethodSignature.class);
		when(joinPoint.getSignature()).thenReturn(signature);
		when(signature.getDeclaringTypeName()).thenReturn("com.ssafy.ExampleService");
		when(signature.getName()).thenReturn("run");
	}

	@Test
	void proceedsOnceAndReturnsSuccessfulResult() throws Throwable {
		Object result = new Object();
		when(joinPoint.proceed()).thenReturn(result);

		assertSame(result, aspect.logExecution(joinPoint, annotation));

		verify(joinPoint).proceed();
	}

	@Test
	void rethrowsOriginalFailureAfterRecordingTiming() throws Throwable {
		IllegalStateException failure = new IllegalStateException("boom");
		when(joinPoint.proceed()).thenThrow(failure);

		assertSame(failure, assertThrows(
				IllegalStateException.class, () -> aspect.logExecution(joinPoint, annotation)));

		verify(joinPoint).proceed();
	}

	@Test
	void appliesThroughSpringProxyToExplicitlyAnnotatedBeanMethod() {
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(AopTestConfiguration.class)) {
			AnnotatedService service = context.getBean(AnnotatedService.class);

			assertSame("ok", service.success());
			assertThrows(IllegalStateException.class, service::failure);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAspectJAutoProxy
	static class AopTestConfiguration {

		@Bean
		ExecutionLoggingAspect executionLoggingAspect() {
			return new ExecutionLoggingAspect();
		}

		@Bean
		AnnotatedService annotatedService() {
			return new AnnotatedService();
		}
	}

	static class AnnotatedService {

		@LogExecution
		public String success() {
			return "ok";
		}

		@LogExecution
		public void failure() {
			throw new IllegalStateException("expected failure");
		}
	}
}
