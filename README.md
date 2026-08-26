# HW02 — Dò Lỗi & Tối Ưu: Xử Lý Bẫy Ô Nhiễm Standard Output (Stdio Pollution) (SS12)

**Học viên:** Nguyễn Đăng Dương — **Lớp:** CNTT2 — **Bài:** SS12 — HW02

**Công nghệ:** Spring Boot 3.5.16 · Spring AI MCP Server (Stdio) · Logback

---

## 1. Tổng quan

RikkeiExpress cần một **MCP Server Spring Boot** chạy qua **Stdio transport** để tích hợp với
**Claude Desktop** đọc database schema PostgreSQL cục bộ. Khi cấu hình vào
`claude_desktop_config.json`, Claude Desktop báo lỗi:

```
[Error] Failed to parse JSON-RPC message from MCP Server.
Unexpected token '  .   ____          _            __ _ _', line 1, column 3.
Connection terminated unexpectedly.
```

**Nguyên nhân gốc:** MCP Stdio transport quy định Client và Server trao đổi **JSON-RPC 2.0**
trên luồng **Standard Output (System.out)**. Spring Boot mặc định in **ASCII Banner quảng cáo**
và Logback mặc định ghi log ra **System.out** — làm biến dạng dữ liệu JSON-RPC, khiến Claude
Desktop không parse được và cắt kết nối.

## 2. Phân tích nguyên nhân kỹ thuật

### 2.1. Giao thức Stdio của MCP yêu cầu System.out sạch

```
Claude Desktop (MCP Client)                 MCP Server (Spring Boot)
        │                                          │
        │  spawn: java -jar logistics-mcp.jar      │
        │ ────────────────────────────────────────>│
        │  stdin  (Client -> Server request JSON-RPC)   ┌─ stdin pipe
        │ <────────────────────────────────────────     │
        │                                          │
        │  stdout (Server -> Client response JSON-RPC)  ┌─ stdout pipe
        │ <────────────────────────────────────────     │
        │     ... chỉ được chứa message JSON-RPC ...    │
```

- Client **spawn** Server như một tiến trình con.
- **stdin** → Server đọc request JSON-RPC từ Client.
- **stdout** → Client đọc response JSON-RPC từ Server.
- **stderr** → (có thể dùng) để log — Client thường bỏ qua hoặc chuyển vào file log riêng.

> **Quy tắc sắt:** Kênh **stdout** là *kênh duy nhất* để vận chuyển **JSON-RPC**. Nếu bất kỳ
> output nào khác (banner, log) dính vào stdout, Client sẽ nhận một chuỗi không phải JSON-RPC.

### 2.2. Vì sao banner + log ra System.out làm tê liệt giao thức

1. **ASCII Banner** (`SpringApplication.run`) in chuỗi
   `.  ____          _  ...` ngay đầu stdout, TRƯỚC khi Server kịp thông báo `initialize`.
2. MCP Client đọc dòng đầu tiên của stdout và **cố gắng parse như một message JSON-RPC** → gặp
   ký tự không hợp lệ → lỗi `Unexpected token ... line 1, column 3`.
3. Lỗi parse làm Client **terminate kết nối**, tiến trình Server bị hủy. Vòng khởi động lặp lại →
   `Connection terminated unexpectedly`.
4. Tương tự, **Logback** mặc định (`ConsoleAppender` với `target=System.out`) ghi log INFO/DEBUG
   chèn vào giữa luồng JSON-RPC → làm vỡ protocol bất kỳ lúc nào có log.

Banner + log ra stdout = **ô nhiễm (pollution)** kênh vận chuyển dữ liệu nhị phân của MCP.

## 3. Bản vá mã nguồn Java (`LogisticsMcpServerApplication.java`)

Tắt **hoàn toàn Banner** bằng `Banner.Mode.OFF` — tạo instance `SpringApplication` thủ công thay
vì gọi `SpringApplication.run(...)` tĩnh:

```java
package com.rikkei.mcp;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LogisticsMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LogisticsMcpServerApplication.class);
        app.setBannerMode(Banner.Mode.OFF);   // <<< TẮT banner ASCII
        app.run(args);
    }
}
```

> `Banner.Mode.OFF` được lưu trong cấu hình của `SpringApplication`, đảm bảo banner không bao
> giờ được in ra **dù ở mode console hay log**. Kèm `spring.main.banner-mode: "off"` trong
> `application.yml` để tăng lớp phòng thủ.

## 4. Bản vá cấu hình Logback (`logback-spring.xml`)

Chuyển toàn bộ log (INFO, DEBUG, ERROR) sang **Standard Error (System.err)**:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <!-- Appender ghi ra System.err: hoàn toàn tách khỏi System.out (kênh JSON-RPC) -->
    <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
        <target>System.err</target>
    </appender>

    <!-- Appender file dự phòng lưu vết log dài hạn -->
    <appender name="FILE" class="ch.qos.logback.core.FileAppender">
        <file>${LOG_PATH:-./logs}/logistics-mcp.log</file>
        <append>true</append>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- Gốc: mọi log đi qua STDERR + FILE, KHÔNG ra System.out -->
    <root level="INFO">
        <appender-ref ref="STDERR" />
        <appender-ref ref="FILE" />
    </root>

    <logger name="com.rikkei.mcp" level="DEBUG" additivity="true" />

</configuration>
```

## 5. Giải thích cơ chế: vì sao log sang System.err vẫn theo dõi được mà không vỡ JSON-RPC?

1. **Kênh tách biệt về mặt hệ điều hành:** `stdout` và `stderr` là **2 luồng độc lập** trong một
   tiến trình (cùng được trỏ bởi descriptor `1` và `2`). Khác với việc tắt log hẳn, ghi vào
   `stderr` **không can thiệp** vào dữ liệu đang chảy trên `stdout`.
2. **MCP Client đọc JSON-RPC đúng từ `stdout`:** Claude Desktop theo chuẩn MCP chỉ đọc response
   từ `stdout`. `stderr` thường được bỏ qua hoặc chuyển tới một tệp log của riêng Client.
   → Kênh JSON-RPC luôn sạch, protocol không bị bóp méo.
3. **Lập trình viên vẫn theo dõi được log:** Vì `stderr`, theo mặc định của terminal/shell, vẫn
   hiển thị ra **cửa sổ console** hoặc được chuyển vào `logistics-mcp.log` qua tệp log dự phòng.
   Khi chạy thủ công `java -jar ...`, banner/log hiện đỏ trên console (stderr) — dev vẫn đọc
   được thông tin khởi động, ngoại lệ, stacktrace… nhưng **không đụng vào stdout**.
4. **Debug dễ dàng tách luồng:** dev có thể chạy `java -jar app.jar 1>mcp.json 2>app.log` —
   dòng `1>` ép stdout (JSON-RPC) vào file mcp.json, `2>` ép log vào app.log, chứng minh hai kênh
   hoàn toàn tách biệt và kiểm chứng chuỗi JSON-RPC nguyên vẹn.

## 6. Minh chứng kiểm chứng chuỗi JSON-RPC sạch

Chạy thủ công và tách luồng để xác minh:

```bash
java -jar build/libs/hw02-nguyendangduong-cntt2-it213-ss12-0.0.1-SNAPSHOT.jar \
     1>mcp-stdout.jsonl 2>app-stderr.log
```

Kết quả mong đợi:
- `mcp-stdout.jsonl` — chỉ chứa **các dòng JSON-RPC hợp lệ** (không banner, không log):
  ```json
  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
  {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26",...}}
  ```
- `app-stderr.log` — chứa **toàn bộ log** của Spring Boot/Logback:
  ```
  [main] INFO  o.s.b.StartupInfoLogger - Starting LogisticsMcpServerApplication
  [main] INFO  LoggingInitializationException - No active profile set
  ...
  ```

> Kết luận: banner/log KHÔNG còn xuất hiện trên `stdout` → Claude Desktop parse được JSON-RPC,
> kết nối Stdio hoạt động ổn định, đồng thời dev vẫn đủ log để giám sát.

---

**Cấu trúc project**

```
hw02-nguyendangduong-cntt2-it213-ss12/
├── build.gradle                       # Spring Boot 3.5.16 + Spring AI MCP Server
├── src/main/java/com/rikkei/mcp/
│   └── LogisticsMcpServerApplication.java   # BẢN VÁ: tắt banner qua Banner.Mode.OFF
└── src/main/resources/
    ├── application.yml                # spring.main.banner-mode=off (phòng thủ tầng 2)
    └── logback-spring.xml             # BẢN VÁ: toàn bộ log -> System.err
```

**Link GitHub:** https://github.com/pedguedes090/hw02-nguyendangduong-cntt2-it213-ss12.git