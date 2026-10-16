package com.excel.common.error;

import com.excel.exportjob.application.ExportFileNotFoundException;
import com.excel.exportjob.application.ExportFileNotReadyException;
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

    /** 작업은 있지만 아직 완료되지 않음: 요청 자체는 올바르나 현재 상태와 충돌하므로 409. */
    @ExceptionHandler(ExportFileNotReadyException.class)
    public ProblemDetail handleFileNotReady(ExportFileNotReadyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Export file not ready");
        problem.setProperty("jobId", e.getJobId());
        problem.setProperty("status", e.getStatus().dbValue());
        return problem;
    }

    @ExceptionHandler(ExportFileNotFoundException.class)
    public ProblemDetail handleFileNotFound(ExportFileNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Export file not found");
        problem.setProperty("jobId", e.getJobId());
        return problem;
    }
}
