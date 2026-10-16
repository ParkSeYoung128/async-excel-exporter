package com.excel.exportjob.api;

import com.excel.exportjob.application.ExportFile;
import com.excel.exportjob.application.ExportFileService;
import com.excel.exportjob.application.ExportJobService;
import com.excel.exportjob.domain.ExportJob;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/export-jobs")
public class ExportJobController {

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExportJobService exportJobService;
    private final ExportFileService exportFileService;

    public ExportJobController(ExportJobService exportJobService, ExportFileService exportFileService) {
        this.exportJobService = exportJobService;
        this.exportFileService = exportFileService;
    }

    /**
     * 생성 요청은 "처리 접수"이므로 202 Accepted. 상태는 Location 의 단건 조회로 확인한다.
     */
    @PostMapping
    public ResponseEntity<ExportJobResponse> create() {
        ExportJob job = exportJobService.create();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(job.getId())
                .toUri();
        return ResponseEntity.accepted()
                .location(location)
                .body(ExportJobResponse.from(job));
    }

    @GetMapping
    public ExportJobPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ExportJobPageResponse.from(exportJobService.list(page, size));
    }

    @GetMapping("/{jobId}")
    public ExportJobResponse get(@PathVariable long jobId) {
        return ExportJobResponse.from(exportJobService.get(jobId));
    }

    /**
     * 완료된 작업의 엑셀 파일을 내려준다.
     * 파일 전체를 메모리에 올리지 않고 스트리밍하며, Content-Length 와 Range(이어받기)는 Spring 이 Resource 기준으로 처리한다.
     * 한글 파일명은 RFC 6266 filename* (UTF-8) 로 인코딩한다.
     */
    @GetMapping("/{jobId}/file")
    public ResponseEntity<Resource> download(@PathVariable long jobId) {
        ExportFile file = exportFileService.prepareDownload(jobId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.downloadName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file.path()));
    }
}
