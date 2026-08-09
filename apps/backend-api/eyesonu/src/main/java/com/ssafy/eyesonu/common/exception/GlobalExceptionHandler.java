package com.ssafy.eyesonu.common.exception;

import com.ssafy.eyesonu.common.api.ApiErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
		return ResponseEntity.status(exception.getStatus())
				.body(ApiErrorResponse.of(
						exception.getStatus().value(), exception.getCode(), exception.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		String message = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return badRequest(message.isBlank() ? "요청 값이 올바르지 않습니다." : message);
	}

	@ExceptionHandler({
			ConstraintViolationException.class,
			HttpMessageNotReadableException.class,
			MissingRequestHeaderException.class,
			MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class
	})
	public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception exception) {
		return badRequest("요청 값이 올바르지 않습니다.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
			HttpMediaTypeNotSupportedException exception) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
				.body(ApiErrorResponse.of(
						415,
						"UNSUPPORTED_MEDIA_TYPE",
						"Content-Type은 application/json이어야 합니다."));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ApiErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(ApiErrorResponse.of(413, "FILE_TOO_LARGE", "파일 허용 용량을 초과했습니다."));
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException exception) {
		log.error(
				"request_exception statusCode={} errorCode={}",
				HttpStatus.SERVICE_UNAVAILABLE.value(),
				"DATABASE_UNAVAILABLE",
				exception);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ApiErrorResponse.of(503, "DATABASE_UNAVAILABLE", "요청을 처리할 수 없습니다."));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiErrorResponse.of(404, "RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
		log.error(
				"request_exception statusCode={} exceptionType={}",
				HttpStatus.INTERNAL_SERVER_ERROR.value(),
				exception.getClass().getName(),
				exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiErrorResponse.of(
						500, "INTERNAL_SERVER_ERROR", "Internal server error."));
	}

	private ResponseEntity<ApiErrorResponse> badRequest(String message) {
		return ResponseEntity.badRequest()
				.body(ApiErrorResponse.of(400, "VALIDATION_ERROR", message));
	}
}
