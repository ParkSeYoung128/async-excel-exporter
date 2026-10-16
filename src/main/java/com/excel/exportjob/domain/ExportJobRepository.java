package com.excel.exportjob.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상태 전이는 엔티티 변경 감지 대신 조건부 UPDATE 로 수행한다.
 * <ul>
 *   <li>WHERE status = '이전 상태' 조건으로 전이가 원자적이며, 반환된 행 수로 성공 여부를 판단한다.</li>
 *   <li>시간은 DB now() 로 기록해 시간 출처를 하나로 통일한다 (네이티브 쿼리를 쓰는 이유).</li>
 *   <li>clearAutomatically: 벌크 UPDATE 후 영속성 컨텍스트에 남은 이전 상태의 엔티티를 비운다.</li>
 * </ul>
 */
public interface ExportJobRepository extends JpaRepository<ExportJob, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE export_job
               SET status = 'processing', started_at = now()
             WHERE id = :id AND status = 'pending'
            """, nativeQuery = true)
    int claim(@Param("id") long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE export_job
               SET status = 'done', finished_at = now(),
                   file_path = :filePath, row_count = :rowCount, error_message = NULL
             WHERE id = :id AND status = 'processing'
            """, nativeQuery = true)
    int markDone(@Param("id") long id, @Param("filePath") String filePath, @Param("rowCount") int rowCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE export_job
               SET status = 'failed', finished_at = now(), error_message = :errorMessage
             WHERE id = :id AND status = 'processing'
            """, nativeQuery = true)
    int markFailed(@Param("id") long id, @Param("errorMessage") String errorMessage);

    /**
     * 재시작 복구: 이전 프로세스가 남긴 processing 을 정리한다.
     * 시도 횟수가 남았으면 pending 으로 되돌리고, 다 썼으면 failed 로 끝낸다.
     * (SET 절의 retry_count 는 모두 갱신 전 값을 참조한다)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE export_job
               SET retry_count   = retry_count + 1,
                   status        = CASE WHEN retry_count + 1 < :maxAttempts THEN 'pending' ELSE 'failed' END,
                   started_at    = CASE WHEN retry_count + 1 < :maxAttempts THEN NULL ELSE started_at END,
                   finished_at   = CASE WHEN retry_count + 1 < :maxAttempts THEN NULL ELSE now() END,
                   error_message = CASE WHEN retry_count + 1 < :maxAttempts THEN error_message
                                        ELSE 'Interrupted by restart ' || (retry_count + 1) || ' times' END
             WHERE status = 'processing'
            """, nativeQuery = true)
    int recoverInterrupted(@Param("maxAttempts") int maxAttempts);

    /**
     * watchdog: processing 이 제한 시간을 넘긴 job 을 failed 로 정리한다.
     * 뒤늦게 워커가 끝나도 markDone 은 0건 갱신이 되어 결과 파일이 삭제된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE export_job
               SET status = 'failed', finished_at = now(),
                   error_message = 'Timed out: processing exceeded ' || :timeoutSeconds || ' seconds'
             WHERE status = 'processing'
               AND started_at < now() - (:timeoutSeconds * INTERVAL '1 second')
            """, nativeQuery = true)
    int failTimedOut(@Param("timeoutSeconds") long timeoutSeconds);

    /** 재제출 대상: 오래된 요청부터 (idx_export_job_active 부분 인덱스 사용) */
    @Query(value = """
            SELECT id
              FROM export_job
             WHERE status = 'pending'
             ORDER BY created_at, id
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPendingIds(@Param("limit") int limit);
}
