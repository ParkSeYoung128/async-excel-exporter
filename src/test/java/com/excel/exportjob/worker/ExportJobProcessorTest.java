package com.excel.exportjob.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.excel.exportjob.application.ExportJobStateService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportJobProcessorTest {

    private final ExportJobStateService stateService = mock(ExportJobStateService.class);
    private final ExportFileGenerator generator = mock(ExportFileGenerator.class);
    private final ExportJobProcessor processor = new ExportJobProcessor(stateService, generator, new SimpleMeterRegistry());

    @Test
    @DisplayName("종료가 시작되면 큐에 남은 작업은 claim 하지 않아 pending 으로 남는다")
    void shuttingDown_skipsWithoutClaim() {
        processor.onShutdown();

        processor.process(1L);

        verifyNoInteractions(stateService, generator);
    }

    @Test
    @DisplayName("claim 에 실패한 job 은 생성하지 않는다")
    void notClaimed_skipsGeneration() {
        given(stateService.claim(1L)).willReturn(false);

        processor.process(1L);

        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("실패 기록까지 실패해도 예외가 executor 로 전파되지 않는다")
    void markFailedFailure_isNotPropagated() {
        given(stateService.claim(1L)).willReturn(true);
        given(generator.generate(1L)).willThrow(new IllegalStateException("generation failed"));
        willThrow(new IllegalStateException("db down")).given(stateService).markFailed(anyLong(), anyString());

        assertThatCode(() -> processor.process(1L)).doesNotThrowAnyException();
        verify(stateService, never()).markDone(anyLong(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("claim 중 예외가 나도 전파되지 않는다")
    void claimFailure_isNotPropagated() {
        given(stateService.claim(1L)).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> processor.process(1L)).doesNotThrowAnyException();
        verifyNoInteractions(generator);
    }

    @Test
    @DisplayName("완료 기록 중 예외가 나면 생성된 파일을 지우고 failed 로 기록한다")
    void markDoneFailure_deletesFileAndMarksFailed(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("1.xlsx"));
        given(stateService.claim(1L)).willReturn(true);
        given(generator.generate(1L)).willReturn(new ExportResult(file.toString(), 100));
        given(stateService.markDone(1L, file.toString(), 100)).willThrow(new IllegalStateException("db down"));

        processor.process(1L);

        assertThat(file).doesNotExist();
        verify(stateService).markFailed(org.mockito.ArgumentMatchers.eq(1L), anyString());
    }

    @Test
    @DisplayName("이미 processing 이 아니어서 완료가 기록되지 않으면 생성된 파일만 지운다")
    void markDoneNotApplied_deletesFile(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("1.xlsx"));
        given(stateService.claim(1L)).willReturn(true);
        given(generator.generate(1L)).willReturn(new ExportResult(file.toString(), 100));
        given(stateService.markDone(1L, file.toString(), 100)).willReturn(false);

        processor.process(1L);

        assertThat(file).doesNotExist();
        verify(stateService, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("완료가 기록되면 파일을 유지한다")
    void markDoneApplied_keepsFile(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("1.xlsx"));
        given(stateService.claim(1L)).willReturn(true);
        given(generator.generate(1L)).willReturn(new ExportResult(file.toString(), 100));
        given(stateService.markDone(1L, file.toString(), 100)).willReturn(true);

        processor.process(1L);

        assertThat(file).exists();
    }
}
