-- =====================================================================
-- 스키마 정의 (PostgreSQL initdb: 데이터 볼륨이 비어 있을 때 1회 실행)
-- =====================================================================

-- 주문 원천 데이터. 컬럼·타입은 과제 명세를 그대로 따른다.
-- 엑셀 생성은 PK 순으로 전체를 읽으므로 PK 외 인덱스는 두지 않는다 (시드 삽입 속도에도 유리)
CREATE TABLE orders (
    id           SERIAL       PRIMARY KEY,
    user_name    VARCHAR(50)  NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    category     VARCHAR(30)  NOT NULL,
    amount       INTEGER      NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    order_date   TIMESTAMP    NOT NULL,
    CONSTRAINT chk_orders_amount CHECK (amount >= 0),
    CONSTRAINT chk_orders_status CHECK (status IN ('confirmed', 'cancelled', 'pending'))
);

COMMENT ON TABLE  orders              IS '주문 (엑셀 추출 대상)';
COMMENT ON COLUMN orders.amount       IS '결제금액 (원)';
COMMENT ON COLUMN orders.status       IS '주문상태: confirmed / cancelled / pending';

-- 엑셀 생성 작업. 상태 전이: pending -> processing -> done / failed
CREATE TABLE export_job (
    id            BIGSERIAL    PRIMARY KEY,
    status        VARCHAR(20)  NOT NULL DEFAULT 'pending',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    file_path     VARCHAR(255),
    row_count     INTEGER,
    retry_count   INTEGER      NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT chk_export_job_status CHECK (status IN ('pending', 'processing', 'done', 'failed')),
    -- 완료된 작업은 반드시 파일 경로를 가진다 (애플리케이션 버그를 DB 차원에서 차단)
    CONSTRAINT chk_export_job_done_has_file CHECK (status <> 'done' OR file_path IS NOT NULL)
);

COMMENT ON TABLE  export_job             IS '엑셀 생성 작업';
COMMENT ON COLUMN export_job.created_at  IS '요청일시';
COMMENT ON COLUMN export_job.retry_count IS '재시작 복구 시 재시도 횟수';

-- 목록 화면: 최신 요청순 조회
CREATE INDEX idx_export_job_created_at ON export_job (created_at DESC);

-- 재시작 복구·재제출: 활성 작업만 조회 (완료된 작업이 쌓여도 인덱스 크기가 커지지 않음)
CREATE INDEX idx_export_job_active ON export_job (status)
    WHERE status IN ('pending', 'processing');
