package com.healtheat.healtheat_api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AuthDtos.ErrorResponse> status(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        return ResponseEntity.status(status).body(new AuthDtos.ErrorResponse(status, HttpStatus.valueOf(status).name(), ex.getReason()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AuthDtos.ErrorResponse> invalid(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new AuthDtos.ErrorResponse(400, "INVALID_REQUEST", "Invalid request fields"));
    }
}
