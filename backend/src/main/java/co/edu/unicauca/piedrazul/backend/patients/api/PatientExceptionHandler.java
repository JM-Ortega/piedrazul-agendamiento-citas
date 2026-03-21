package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = PatientController.class)
@Slf4j
public class PatientExceptionHandler {

    @ExceptionHandler(PatientAlreadyExistsException.class)
    public ProblemDetail handlePatientAlreadyExists(
            PatientAlreadyExistsException ex,
            HttpServletRequest request
    ) {
        log.warn("Patient already exists: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setTitle("Patient already exists");
        problem.setType(URI.create("https://piedrazul/errors/patient-already-exists"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ProblemDetail handlePatientNotFound(
            PatientNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Patient not found: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setTitle("Patient not found");
        problem.setType(URI.create("https://piedrazul/errors/patient-not-found"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(PatientAlreadyLinkedUserException.class)
    public ProblemDetail handlePatientAlreadyLinkedUser(
            PatientAlreadyLinkedUserException ex,
            HttpServletRequest request
    ) {
        log.warn("Patient already linked to user: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
        problem.setTitle("Patient already linked to user");
        problem.setType(URI.create("https://piedrazul/errors/patient-already-linked"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(InvalidPatientDataException.class)
    public ProblemDetail handleInvalidPatientData(
            InvalidPatientDataException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid patient data: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setTitle("Invalid patient data");
        problem.setType(URI.create("https://piedrazul/errors/invalid-patient-data"));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String detail = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst() // o puedes juntar todos
                .orElse("Validation error");

        log.warn("Validation error: {}", detail);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                detail
        );
        problem.setTitle("Validation error");
        problem.setType(URI.create("https://piedrazul/errors/validation-error"));
        problem.setInstance(URI.create(request.getRequestURI()));

        return problem;
    }
}