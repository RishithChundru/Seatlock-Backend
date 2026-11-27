package com.seatlock.api;

import com.seatlock.service.BookingService.SeatUnavailableException;
import com.seatlock.service.BookingService;
import com.seatlock.service.EventService;
import com.seatlock.service.WaitlistService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private final com.seatlock.config.SeatlockMetrics metrics;

    public ApiExceptionHandler(com.seatlock.config.SeatlockMetrics metrics) { this.metrics = metrics; }

    @ExceptionHandler({SeatUnavailableException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail seatTaken(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/holds")) metrics.holdConflict(); else metrics.bookingConflict();
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, new SeatUnavailableException().getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request conflicts with existing data, please retry");
    }

    @ExceptionHandler(org.springframework.data.redis.RedisConnectionFailureException.class)
    ProblemDetail redisDown() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Seat holds are temporarily unavailable, please retry");
    }

    /** DB pool exhausted or database unreachable: tell clients to back off and retry, don't 500. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> overloaded() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "We're busy right now. Please try again in a moment."));
    }

    @ExceptionHandler({WaitlistService.SeatsStillAvailableException.class,
                       BookingService.CheckInRefusedException.class, com.seatlock.service.SeatHoldService.HoldExpiredException.class,
                       com.seatlock.service.PaymentService.PaymentRefundedException.class})
    ProblemDetail seatsAvailable(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(com.seatlock.service.RazorpayClient.ProviderException.class)
    ProblemDetail paymentProvider(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
