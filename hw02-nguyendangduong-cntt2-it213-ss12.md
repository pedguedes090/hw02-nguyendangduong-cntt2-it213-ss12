# Bài 2: Xử lý ô nhiễm Standard Output trong MCP Stdio

**Sinh viên:** Nguyễn Đăng Dương  
**Lớp:** CNTT2  
**Môn học:** IT213  
**Session:** 12

## 1. Nguyên nhân kỹ thuật

Với Stdio Transport, `stdin` và `stdout` không còn là console thông thường mà trở thành **kênh giao thức** giữa MCP Client và MCP Server. Mỗi dòng/khung dữ liệu trên `stdout` phải là một thông điệp JSON-RPC hợp lệ, ví dụ:

```json
{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}
```

Khi Spring Boot khởi động mặc định, banner ASCII bắt đầu bằng chuỗi `  .   ____` và Logback cũng có thể ghi các dòng timestamp/INFO vào `System.out`. MCP Client đọc byte đầu tiên từ `stdout`, mong đợi JSON nhưng nhận dấu chấm hoặc nội dung log, nên JSON parser báo `Unexpected token`. Client không thể biết byte nào là log và byte nào là JSON-RPC, vì vậy mất đồng bộ khung thông điệp và đóng kết nối.

Hậu quả không chỉ xảy ra lúc khởi động. Bất kỳ `System.out.println(...)` nào xuất hiện giữa hai thông điệp hoặc trong lúc đang serialize một phản hồi cũng có thể làm hỏng phiên MCP. Vì vậy quy tắc của server Stdio là:

```text
stdout = chỉ JSON-RPC của MCP
stderr = banner/log/chẩn đoán dành cho con người
```

## 2. Bản vá `LogisticsMcpServerApplication.java`

```java
package com.rikkei.mcp;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogisticsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication application =
                new SpringApplication(LogisticsMcpServerApplication.class);

        // stdout phải dành riêng cho JSON-RPC của MCP Stdio.
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }
}
```

`Banner.Mode.OFF` tắt hoàn toàn banner thay vì chỉ chuyển banner sang vị trí khác. Trong mã nghiệp vụ cũng không được dùng `System.out.println`; mọi thông tin chẩn đoán phải đi qua logger đã cấu hình hoặc `System.err`.

## 3. Bản vá `src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration scan="false">
    <!-- Không dùng ConsoleAppender mặc định vì mặc định có thể ghi ra stdout. -->
    <property name="CONSOLE_LOG_PATTERN"
              value="%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} - %msg%n"/>

    <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
        <target>System.err</target>
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <charset>UTF-8</charset>
            <pattern>${CONSOLE_LOG_PATTERN}</pattern>
        </encoder>
    </appender>

    <!-- DEBUG bao gồm cả DEBUG, INFO, WARN và ERROR. -->
    <root level="DEBUG">
        <appender-ref ref="STDERR"/>
    </root>
</configuration>
```

Nếu không cần log DEBUG của toàn bộ framework trong môi trường production, có thể giảm nhiễu nhưng vẫn giữ log ứng dụng bằng cấu hình sau:

```xml
<logger name="org.springframework" level="INFO"/>
<logger name="com.rikkei.mcp" level="DEBUG"/>
```

Hai dòng này được đặt trước thẻ `<root>`; chúng không đổi đích ghi, mọi log vẫn đi đến appender `STDERR`.

## 4. Vì sao `System.err` không làm hỏng MCP

Hệ điều hành cung cấp hai file descriptor tách biệt:

- Standard Output là kênh dữ liệu giao thức mà MCP Client parse thành JSON-RPC.
- Standard Error là kênh chẩn đoán; Claude Desktop hoặc terminal có thể thu thập và hiển thị riêng.

Do hai luồng độc lập, logger vẫn ghi đầy đủ DEBUG/INFO/ERROR để lập trình viên theo dõi, nhưng các byte log không bị trộn vào chuỗi JSON-RPC. Việc chuyển log sang `stderr` không có nghĩa là bỏ log; nó chỉ thực hiện phân tách đúng giữa **machine-readable protocol** và **human-readable diagnostics**.

## 5. Checklist kiểm chứng

1. Khởi động server và redirect hai luồng ra hai file riêng.

```powershell
java -jar logistics-mcp-server.jar 1>stdout.txt 2>stderr.txt
```

2. Gọi MCP Client thực hiện `initialize` và `tools/list`.
3. Xác nhận `stdout.txt` chỉ chứa JSON-RPC hợp lệ, không có banner, timestamp hoặc tên logger.
4. Xác nhận `stderr.txt` vẫn có log DEBUG/INFO/ERROR.
5. Tìm và loại bỏ mọi `System.out.print*` trong mã ứng dụng.

```powershell
rg "System\.out\.print" src
```

Sau bản vá, JSON parser của Claude Desktop không còn gặp banner ở byte đầu tiên và kết nối Stdio có thể duy trì bình thường.
