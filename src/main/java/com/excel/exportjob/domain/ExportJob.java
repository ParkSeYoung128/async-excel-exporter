package com.excel.exportjob.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;

/**
 * 엑셀 생성 작업.
 * <p>
 * 시간 값은 모두 DB now() 를 출처로 한다 (애플리케이션 시계와 섞이면 타임존·시계 차이로 시간 역전이 생길 수 있음).
 * created_at 은 DB 기본값으로 채워지고, INSERT 직후 다시 읽어 온다.
 * 상태 전이와 started_at / finished_at 기록은 조건부 UPDATE 쿼리로 수행한다 (#9).
 */
@Entity
@Table(name = "export_job")
public class ExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private ExportJobStatus status;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    protected ExportJob() {
    }

    private ExportJob(ExportJobStatus status) {
        this.status = status;
    }

    public static ExportJob pending() {
        return new ExportJob(ExportJobStatus.PENDING);
    }

    public Long getId() {
        return id;
    }

    public ExportJobStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
