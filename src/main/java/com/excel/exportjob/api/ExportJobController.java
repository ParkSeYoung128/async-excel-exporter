package com.excel.exportjob.api;

import com.excel.exportjob.application.ExportJobService;
import com.excel.exportjob.domain.ExportJob;
import java.net.URI;
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

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
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
}
