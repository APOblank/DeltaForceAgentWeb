package com.deltaforce.agentweb.controller;

import com.deltaforce.agentweb.config.JwtAuthenticationInterceptor;
import com.deltaforce.agentweb.service.Neo4jDataCenterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/data-center")
public class DataCenterController {
    private final Neo4jDataCenterService service;

    public DataCenterController(Neo4jDataCenterService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    public Map<String, Object> accounts(HttpServletRequest request) {
        return Collections.<String, Object>singletonMap("accounts", service.accounts(requireUser(request)));
    }

    @PostMapping("/accounts/select")
    public Map<String, Object> selectAccount(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return service.selectAccount(requireUser(request), String.valueOf(body.get("accountId")));
    }

    @DeleteMapping("/accounts/{accountId}")
    public Map<String, Object> deleteAccount(HttpServletRequest request, @PathVariable String accountId) {
        return service.deleteAccount(requireUser(request), accountId);
    }

    @PostMapping("/import")
    public Map<String, Object> importData(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return service.importData(requireUser(request), body);
    }

    @PostMapping(value = "/import/legacy-backup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importLegacyBackup(HttpServletRequest request,
                                                  @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择数据中心 ZIP 备份");
        }
        try {
            return service.importLegacyBackup(requireUser(request), file.getBytes());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备份读取失败：" + ex.getMessage(), ex);
        }
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest request, @RequestParam(required = false) String accountId) {
        return service.summary(requireUser(request), accountId);
    }

    @GetMapping("/records")
    public Map<String, Object> records(
            HttpServletRequest request,
            @RequestParam(required = false) String accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String map,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end
    ) {
        return service.records(requireUser(request), accountId, page, size, map, result, start, end);
    }

    @GetMapping("/records/{battleId}")
    public Map<String, Object> battle(HttpServletRequest request, @PathVariable String battleId) {
        return service.battleDetail(requireUser(request), battleId);
    }

    @GetMapping("/analysis")
    public Map<String, Object> analysis(HttpServletRequest request,
                                        @RequestParam(required = false) String accountId,
                                        @RequestParam(required = false) String start,
                                        @RequestParam(required = false) String end) {
        return service.analysis(requireUser(request), accountId, start, end);
    }

    @GetMapping("/trends")
    public Map<String, Object> trends(HttpServletRequest request,
                                      @RequestParam(required = false) String accountId,
                                      @RequestParam(defaultValue = "day") String bucket,
                                      @RequestParam(required = false) String start,
                                      @RequestParam(required = false) String end) {
        return service.trends(requireUser(request), accountId, bucket, start, end);
    }

    @GetMapping("/items")
    public Map<String, Object> items(HttpServletRequest request,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String start,
                                     @RequestParam(required = false) String end) {
        return service.items(requireUser(request), accountId, start, end);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> exportData(HttpServletRequest request,
                                                           @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=delta-force-records.json")
                .body(service.exportData(requireUser(request), accountId));
    }

    @DeleteMapping("/data")
    public Map<String, Object> clearData(HttpServletRequest request) {
        return service.clearData(requireUser(request));
    }

    private String requireUser(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtAuthenticationInterceptor.AUTHENTICATED_USER_ID);
        if (userId == null || !StringUtils.hasText(String.valueOf(userId))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return String.valueOf(userId);
    }
}
