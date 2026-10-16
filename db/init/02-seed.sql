-- =====================================================================
-- 주문 더미 데이터 100,000건
-- - setseed 로 난수를 고정해 매 초기화마다 같은 데이터를 만든다 (측정 재현성)
-- - 기준 시각도 now() 대신 고정값을 써서 실행 시점과 무관하게 동일하게 만든다
-- =====================================================================

SELECT setseed(0.42);

-- 행마다 난수를 한 번만 뽑아 두기 위해 MATERIALIZED CTE 사용
-- (같은 random() 결과를 category 와 product_name 이 함께 써야 둘이 서로 맞는다)
WITH base AS MATERIALIZED (
    SELECT g,
           floor(random() * 5)::int  AS c,    -- 카테고리 인덱스 0~4
           floor(random() * 10)::int AS ln,   -- 성
           floor(random() * 16)::int AS fn,   -- 이름
           random()                  AS rp,   -- 상품
           random()                  AS ra,   -- 금액
           random()                  AS rs,   -- 상태
           random()                  AS rd    -- 주문일시
    FROM generate_series(1, 100000) AS g
)
INSERT INTO orders (user_name, product_name, category, amount, status, order_date)
SELECT
    (ARRAY['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임'])[ln + 1]
        || (ARRAY['민준', '서연', '도윤', '지우', '하준', '서윤', '시우', '하은',
                  '주원', '지민', '예준', '수아', '건우', '지유', '현우', '채원'])[fn + 1],
    CASE c
        WHEN 0 THEN (ARRAY['리프트 1일권', '리프트 오후권', '스키 시즌패스', '장비 렌탈 패키지', '스키 강습 2시간'])[1 + floor(rp * 5)::int]
        WHEN 1 THEN (ARRAY['워터파크 종일권', '워터파크 오후권', '워터파크 야간권', '워터파크 종일권 + 구명조끼'])[1 + floor(rp * 4)::int]
        WHEN 2 THEN (ARRAY['스탠다드 1박', '디럭스 1박', '패밀리 스위트 1박', '주중 2박 패키지'])[1 + floor(rp * 4)::int]
        WHEN 3 THEN (ARRAY['테마파크 자유이용권', '테마파크 오후 자유이용권', '테마파크 연간회원권'])[1 + floor(rp * 3)::int]
        ELSE        (ARRAY['주중 18홀 라운드', '주말 18홀 라운드', '야간 9홀 라운드'])[1 + floor(rp * 3)::int]
    END,
    (ARRAY['스키', '워터파크', '숙박', '테마파크', '골프'])[c + 1],
    -- 카테고리별 금액 범위 [min, max) 에서 100원 단위
    CASE c
        WHEN 0 THEN  30000 + floor(ra * 1200)::int * 100   --  30,000 ~ 149,900
        WHEN 1 THEN  30000 + floor(ra *  600)::int * 100   --  30,000 ~  89,900
        WHEN 2 THEN  80000 + floor(ra * 3200)::int * 100   --  80,000 ~ 399,900
        WHEN 3 THEN  30000 + floor(ra *  400)::int * 100   --  30,000 ~  69,900
        ELSE        100000 + floor(ra * 2000)::int * 100   -- 100,000 ~ 299,900
    END,
    -- 상태 비율 confirmed 70% / cancelled 15% / pending 15%
    CASE
        WHEN rs < 0.70 THEN 'confirmed'
        WHEN rs < 0.85 THEN 'cancelled'
        ELSE                'pending'
    END,
    -- 고정 기준 시각으로부터 최근 1년 이내, 초 단위
    date_trunc('second', TIMESTAMP '2026-10-01 00:00:00' - rd * INTERVAL '365 days')
FROM base
ORDER BY g;

-- 대량 삽입 직후 통계 갱신
ANALYZE orders;
