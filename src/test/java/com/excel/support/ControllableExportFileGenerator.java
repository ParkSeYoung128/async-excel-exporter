package com.excel.support;

import com.excel.exportjob.worker.ExportFileGenerator;
import com.excel.exportjob.worker.ExportResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 테스트에서 생성 결과(성공 / 실패 / 대기)를 조절하는 가짜 생성기.
 */
public class ControllableExportFileGenerator implements ExportFileGenerator {

    public enum Mode { SUCCEED, FAIL, BLOCK }

    public static final int ROW_COUNT = 100_000;

    private volatile Mode mode = Mode.SUCCEED;
    private volatile CountDownLatch release = new CountDownLatch(0);

    public void reset() {
        release.countDown();
        mode = Mode.SUCCEED;
        release = new CountDownLatch(0);
    }

    public void succeed() {
        mode = Mode.SUCCEED;
    }

    public void fail() {
        mode = Mode.FAIL;
    }

    /** 다음 생성부터 {@link #release()} 가 호출될 때까지 워커 스레드를 붙잡는다. */
    public void block() {
        release = new CountDownLatch(1);
        mode = Mode.BLOCK;
    }

    public void release() {
        mode = Mode.SUCCEED;
        release.countDown();
    }

    @Override
    public ExportResult generate(long jobId) {
        switch (mode) {
            case FAIL -> throw new IllegalStateException("simulated failure for job " + jobId);
            case BLOCK -> await();
            case SUCCEED -> { }
        }
        return new ExportResult("/data/exports/" + jobId + ".xlsx", ROW_COUNT);
    }

    private void await() {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("blocked generator was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
