package com.excel.exportjob.export;

import com.excel.exportjob.config.ExportProperties;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 전체를 한 행씩 흘려보낸다 (메모리에 모으지 않는다).
 * <ul>
 *   <li>JPA 대신 JDBC: 엔티티와 변경 감지용 스냅샷이 영속성 컨텍스트에 트랜잭션 내내 쌓이지 않게 한다.</li>
 *   <li>fetchSize + 트랜잭션: PostgreSQL 드라이버는 autocommit 이 꺼진 상태에서만 커서로 나눠 가져온다.
 *       트랜잭션 밖이면 fetchSize 를 무시하고 전체를 한 번에 메모리로 읽는다.</li>
 *   <li>REPEATABLE READ: 조회 전체가 시작 시점 스냅샷을 보므로, 생성 중 주문이 추가돼도
 *       파일 내용과 row_count 가 항상 일치한다. 대가로 트랜잭션이 길어져 그동안 VACUUM 이 정리를 미룬다.</li>
 * </ul>
 */
@Component
public class OrderExportReader {

    private static final String SQL = """
            SELECT id, user_name, product_name, category, amount, status, order_date
              FROM orders
             ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int fetchSize;
    private final String statementTimeout;

    public OrderExportReader(JdbcTemplate jdbcTemplate, ExportProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.fetchSize = properties.fetchSize();
        this.statementTimeout = properties.queryTimeout().toMillis() + "ms";
    }

    /**
     * @return 흘려보낸 행 수
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public int streamAll(Consumer<OrderRow> consumer) {
        // 이 트랜잭션에만 적용(set_config 의 is_local = true). 커서로 나눠 가져올 때 DB 명령 하나가
        // 락 대기 등으로 응답 없이 멈추는 것을 막는다. 작업 전체 시간은 watchdog(processing-timeout)이 제한한다.
        jdbcTemplate.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, statementTimeout);

        int[] count = {0};
        jdbcTemplate.query(
                connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            SQL, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                    statement.setFetchSize(fetchSize);
                    return statement;
                },
                (RowCallbackHandler) rs -> {
                    consumer.accept(new OrderRow(
                            rs.getInt("id"),
                            rs.getString("user_name"),
                            rs.getString("product_name"),
                            rs.getString("category"),
                            rs.getInt("amount"),
                            rs.getString("status"),
                            rs.getObject("order_date", LocalDateTime.class)));
                    count[0]++;
                });
        return count[0];
    }
}
