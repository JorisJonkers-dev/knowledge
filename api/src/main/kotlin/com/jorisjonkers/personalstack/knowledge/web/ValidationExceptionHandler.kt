package com.jorisjonkers.personalstack.knowledge.web

import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.validation.method.MethodValidationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

@RestControllerAdvice
class ValidationExceptionHandler {
    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        MethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun handleValidationFailure(exc: Exception): ResponseEntity<ProblemDetail> {
        val detail =
            ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exc.message ?: "Validation failed",
            )
        detail.title = "Validation failed"
        return ResponseEntity.unprocessableEntity().body(detail)
    }
}
