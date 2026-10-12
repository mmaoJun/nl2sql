package com.nl2sql.controller;

import com.nl2sql.model.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口。
 *
 * <p>用于部署探活与联调自检，不依赖任何外部资源（数据库 / Redis / 模型服务）。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * 健康检查。
     *
     * @return 固定返回 {@code {"code":200,"message":"success","data":"ok"}}
     */
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.ok("ok");
    }
}
