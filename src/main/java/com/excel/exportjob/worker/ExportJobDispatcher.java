package com.excel.exportjob.worker;

/**
 * 생성된 job 을 실행 주체에게 전달한다.
 * <p>
 * 설계 원칙: "중복 제출은 허용하고, 중복 처리는 claim 이 막는다".
 * 같은 job 이 여러 번 전달돼도 {@code ExportJobStateService#claim} 의 조건부 UPDATE 로 한 번만 처리된다.
 * 다중 인스턴스로 확장할 때는 이 인터페이스의 구현만 SKIP LOCKED 폴링이나 메시지 큐로 바꾸면 된다.
 */
public interface ExportJobDispatcher {

    /**
     * @return 실행 큐에 들어갔으면 true. 큐가 가득 차 거부되면 false (job 은 pending 으로 남는다)
     */
    boolean dispatch(long jobId);
}
