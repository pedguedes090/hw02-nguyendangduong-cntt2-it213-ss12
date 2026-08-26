package com.rikkei.mcp;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogisticsMcpServerApplication {

    public static void main(String[] args) {
        // BẢN VÁ: Tắt hoàn toàn Banner ASCII của Spring Boot
        // (SpringApplication.run(...) mặc định in banner và log ra System.out,
        //  làm vỡ chuỗi JSON-RPC trên Stdio. Ta tạo instance thủ công + set Banner.Mode.OFF)
        SpringApplication app = new SpringApplication(LogisticsMcpServerApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.run(args);
    }
}