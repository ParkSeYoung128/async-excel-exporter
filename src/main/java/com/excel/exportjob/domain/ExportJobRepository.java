package com.excel.exportjob.domain;

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
}
