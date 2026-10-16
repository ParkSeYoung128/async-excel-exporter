package com.excel.common.error;

import com.excel.exportjob.application.ExportJobNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 도메인 예외를 RFC 9457 ProblemDetail 로 변환한다.
 * 프레임워크 예외(잘못된 파라미터 타입 등)는 spring.mvc.problemdetails.enabled 로 같은 형식을 따른다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ExportJobNotFoundException.class)
    public ProblemDetail handleNotFound(ExportJobNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Export job not found");
        problem.setProperty("jobId", e.getJobId());
        return problem;
    }
}
