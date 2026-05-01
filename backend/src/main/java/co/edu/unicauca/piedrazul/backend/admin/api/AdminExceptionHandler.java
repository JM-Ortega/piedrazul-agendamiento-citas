package co.edu.unicauca.piedrazul.backend.admin.api;

import co.edu.unicauca.piedrazul.backend.admin.exception.AdminUserAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AdminSystemUserController.class)
public class AdminExceptionHandler {

    @ExceptionHandler(AdminUserAlreadyExistsException.class)
    public ProblemDetail handleAdminUserAlreadyExists(AdminUserAlreadyExistsException exception) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Usuario existente");
        problem.setDetail(exception.getMessage());
        return problem;
    }
}