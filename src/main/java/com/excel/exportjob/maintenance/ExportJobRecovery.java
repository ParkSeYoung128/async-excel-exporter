package com.excel.exportjob.maintenance;

import com.excel.exportjob.application.ExportJobStateService;
import com.excel.exportjob.config.ExportProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 기동 시 이전 프로세스가 남긴 상태를 정리한다.
 * <p>
 * 단일 인스턴스 전제: 기동 시점의 processing 은 모두 이전 프로세스가 처리하다 중단된 job 이다.
 * (다중 인스턴스라면 다른 인스턴스가 처리 중일 수 있으므로 lease / heartbeat 가 필요하다)
 * <p>
 * 스케줄러(재제출·watchdog)보다 먼저 실행되도록 컨텍스트 초기화 단계(모든 싱글톤 생성 직후)에서 수행한다.
 * 스케줄러가 먼저 돌면 이전 프로세스의 오래된 processing 을 watchdog 이 시도 횟수와 무관하게 failed 로 만들 수 있다.
 * <p>
 * 임시 파일: 비정상 종료가 남기는 파일은 두 종류다.
 * <ul>
 *   <li>{jobId}.xlsx.tmp: 파일로 저장하는 도중 종료된 경우</li>
 *   <li>SXSSF 임시 시트 파일: 생성(조회·쓰기) 도중 종료된 경우. 기본 위치(/tmp)는 컨테이너 재시작 후에도 남으므로
 *       export 볼륨 아래 {@value #POI_TEMP_DIR} 로 모아 두고 기동 시 비운다.</li>
 * </ul>
 */
@Component
public class ExportJobRecovery implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ExportJobRecovery.class);
    private static final String TEMP_SUFFIX = ".xlsx.tmp";
    static final String POI_TEMP_DIR = ".poi-tmp";

    private final ExportJobStateService stateService;
    private final ExportProperties properties;

    public ExportJobRecovery(ExportJobStateService stateService, ExportProperties properties) {
        this.stateService = stateService;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        recover();
        usePoiTempDirectory();
    }

    /**
     * @return pending 또는 failed 로 정리된 processing job 수
     */
    public int recover() {
        int recovered = stateService.recoverInterrupted(properties.maxAttempts());
        Path dir = properties.dir();
        int deleted = deleteFiles(dir, path -> path.getFileName().toString().endsWith(TEMP_SUFFIX))
                + deleteFiles(dir.resolve(POI_TEMP_DIR), path -> true);
        if (recovered > 0 || deleted > 0) {
            log.info("Recovered {} interrupted export job(s), deleted {} temporary file(s)", recovered, deleted);
        }
        return recovered;
    }

    /**
     * SXSSF 임시 시트 파일을 export 볼륨 아래로 모은다. POI 설정은 JVM 전역이므로 기동 시 한 번만 지정한다.
     * 디렉터리를 만들 수 없으면(로컬 실행 등) 기본 위치를 그대로 쓴다.
     */
    private void usePoiTempDirectory() {
        Path poiTempDir = properties.dir().resolve(POI_TEMP_DIR);
        try {
            Files.createDirectories(poiTempDir);
            TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(poiTempDir.toFile()));
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot use {} for POI temporary files. Falling back to the default location", poiTempDir, e);
        }
    }

    private int deleteFiles(Path dir, Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> temps;
        try (Stream<Path> files = Files.list(dir)) {
            temps = files.filter(Files::isRegularFile).filter(filter).toList();
        } catch (IOException e) {
            log.warn("Failed to list directory {}", dir, e);
            return 0;
        }
        int deleted = 0;
        for (Path temp : temps) {
            try {
                if (Files.deleteIfExists(temp)) {
                    deleted++;
                }
            } catch (IOException e) {
                log.warn("Failed to delete temporary export file {}", temp, e);
            }
        }
        return deleted;
    }
}
