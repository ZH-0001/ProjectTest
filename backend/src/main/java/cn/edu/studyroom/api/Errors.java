package cn.edu.studyroom.api;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Api.Failure.class)
    ResponseEntity<?> business(Api.Failure e) { return ResponseEntity.status(e.status).body(Api.error(e.code,e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,MissingServletRequestParameterException.class,IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Api.error("INVALID_PARAMETER","参数格式、必填字段或取值不正确")); }
    @ExceptionHandler({TransientDataAccessException.class,org.springframework.transaction.CannotCreateTransactionException.class})
    ResponseEntity<?> retry(Exception e) { return ResponseEntity.status(503).body(Api.error("RETRYABLE_ERROR","数据库暂时不可用，请稍后重试")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception e) { return ResponseEntity.internalServerError().body(Api.error("INTERNAL_ERROR","服务处理失败，请稍后重试")); }
}
