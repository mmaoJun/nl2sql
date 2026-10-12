package com.nl2sql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * NL2SQL 应用启动入口。
 *
 * <p>启用异步支持（{@code @EnableAsync}），用于审计日志等异步写入场景。
 */
@SpringBootApplication
@EnableAsync
public class Nl2SqlApplication {

    /**
     * 应用入口方法。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Nl2SqlApplication.class, args);
    }
}
