# Bài 4 — Phân tích sự cố Service Discovery khi scale hệ thống

**Session 02 — Từ Monolithic đến Microservice**
**Cấp độ:** Phân tích

---

## 1. Tóm tắt sự cố

FoodX scale `restaurant-service` từ 1 lên 4 instance để phục vụ giờ cao điểm, nhưng
`order-service` vẫn chỉ gọi được đúng **1 instance**. Nguyên nhân gốc rễ nằm ở cấu hình
Eureka Client của `restaurant-service`: sai cú pháp `defaultZone` khiến instance **không
đăng ký (register) thành công** vào Eureka Server. Do đó, dù có 4 tiến trình
`restaurant-service` đang chạy, Eureka Registry thực chất chỉ nhìn thấy được instance cũ
(hoặc hoàn toàn không thấy instance nào mới đăng ký đúng cách), nên `order-service` không
thể load balance sang các instance mới.

---

## 2. Vấn đề 1: `spring.application.name` không nhất quán chữ hoa/thường

### Hiện trạng

```yaml
spring:
  application:
    name: RestaurantService   # trong khi order-service, payment-service viết thường
```

### Phân tích nguyên nhân

- Eureka Client **vẫn đăng ký thành công** với tên `RestaurantService` vì Eureka không bắt
  buộc một quy tắc case cụ thể ở tầng đăng ký — Netflix Eureka Server lưu `serviceId`
  **không phân biệt hoa/thường ở phía server** (Eureka Server tự động uppercase toàn bộ
  `serviceId` trong registry nội bộ), nhưng phía client — đặc biệt là các thư viện tra cứu
  như `DiscoveryClient.getInstances(serviceId)` hoặc Feign `@FeignClient(name = "...")` —
  lại **rất nhạy với chuỗi bạn truyền vào** khi đọc code, khi cấu hình, khi log, khi giám
  sát.
- Hệ quả thực tế dù ứng dụng "chạy được":
  1. **Khó tra cứu, dễ nhầm lẫn khi đọc log/dashboard Eureka**: Trên Eureka Dashboard,
     server hiển thị tên service dưới dạng UPPERCASE (`RESTAURANTSERVICE`), khác hẳn với
     format `ORDER-SERVICE`, `PAYMENT-SERVICE` của các service khác — người vận hành khó
     nhận diện theo pattern chung, dễ tra nhầm hoặc bỏ sót khi rà soát nhiều service.
  2. **Không đồng nhất trong cấu hình Feign/Ribbon/Gateway**: Nếu một service khác dùng
     Spring Cloud Gateway với route dạng `lb://restaurant-service`, việc tên đăng ký thực
     tế là `RestaurantService` (khác hoàn toàn quy ước `kebab-case` toàn hệ thống) làm tăng
     rủi ro gõ sai tên khi cấu hình route, và khó áp dụng linting/naming-convention tự động.
  3. **Khó maintain khi hệ thống scale lên hàng chục service**: Không có một convention đặt
     tên rõ ràng khiến việc tự động hóa (script kiểm tra sức khỏe, generate tài liệu API,
     tạo dashboard theo pattern tên) trở nên phức tạp hơn, vì phải xử lý nhiều biến thể case
     khác nhau.
  4. **Rủi ro khi tích hợp case-sensitive tooling**: Một số công cụ bên thứ 3 (service mesh,
     API gateway ngoài Spring Cloud) có thể so khớp tên service theo string chính xác — nếu
     không đồng nhất case, việc tích hợp sẽ phát sinh lỗi khó debug.

### Khuyến nghị

Chuẩn hóa toàn hệ thống theo `kebab-case` chữ thường, đúng convention Spring Cloud:

```yaml
spring:
  application:
    name: restaurant-service
```

---

## 3. Vấn đề 2: Thiếu dấu `/` ở cuối `defaultZone` — nguyên nhân gốc rễ khiến không đăng ký được

### Hiện trạng (SAI)

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka
```

### Vì sao đây là lỗi nghiêm trọng nhất

- Thư viện `eureka-client` build URL đăng ký/tra cứu bằng cách **nối chuỗi trực tiếp**
  giữa `defaultZone` và các endpoint con (`apps/`, `apps/{appId}`, …).
- Nếu `defaultZone` thiếu dấu `/` cuối, request đăng ký thực tế sẽ bị nối sai thành:

  ```
  http://eureka-server:8761/eurekaapps/RESTAURANT-SERVICE   ❌ (sai, thiếu dấu /)
  ```

  thay vì đúng phải là:

  ```
  http://eureka-server:8761/eureka/apps/RESTAURANT-SERVICE  ✅
  ```

- Kết quả: request đăng ký (`POST /eureka/apps/{appId}`) bị gửi sai endpoint → Eureka
  Server trả về lỗi (404/không nhận diện được path) → Client không đăng ký (register)
  thành công, mặc dù ứng dụng vẫn khởi động bình thường và **không hề crash** — đây chính
  là lý do sự cố "âm thầm", rất khó phát hiện nếu không xem kỹ log Eureka Client (thường có
  log dạng `DiscoveryClient_RESTAURANT-SERVICE - registration failed` hoặc lỗi kết nối).
- Vì chỉ có tối đa 1 trong 4 instance (thường là instance khởi động sớm nhất hoặc đã đăng
  ký từ trước khi lỗi cấu hình được deploy) là còn nằm trong registry, nên `order-service`
  chỉ thấy đúng 1 instance để gọi tới, dẫn đến hiện tượng nghẽn tải lên đúng 1 instance đó.

### Sửa đúng

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/
```

**Lưu ý:** dấu `/` cuối là **bắt buộc**. Đây là quy ước chính thức của Eureka Client — nếu
có nhiều Eureka Server (HA), các URL cách nhau bởi dấu phẩy, mỗi URL đều phải kết thúc bằng
`/`, ví dụ:

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server-1:8761/eureka/,http://eureka-server-2:8761/eureka/
```

---

## 4. Cơ chế Heartbeat của Eureka và tình huống crash đột ngột

### Cơ chế hoạt động

Eureka dùng mô hình **self-preservation + lease renewal (heartbeat)** để theo dõi tình
trạng "sống" của từng instance:

| Thông số | Mặc định | Ý nghĩa |
|---|---|---|
| `eureka.instance.lease-renewal-interval-in-seconds` | 30s | Client gửi heartbeat (renew lease) tới Server mỗi 30 giây |
| `eureka.instance.lease-expiration-duration-in-seconds` | 90s | Nếu Server không nhận được heartbeat nào trong 90 giây liên tục, coi instance đã "chết" và loại khỏi registry |
| `eureka.client.registry-fetch-interval-seconds` | 30s | Chu kỳ Client (order-service) tải lại registry từ Server về cache cục bộ |

### Khi `restaurant-service-2` crash đột ngột (không kịp gửi tín hiệu deregister)

1. **Instance crash** → không còn gửi heartbeat (`PUT /eureka/apps/{appId}/{instanceId}`)
   định kỳ mỗi 30s tới Eureka Server nữa.
2. **Eureka Server chờ tối đa 90 giây** (theo `lease-expiration-duration-in-seconds`) kể từ
   lần heartbeat cuối cùng. Nếu hết 90 giây mà không có heartbeat mới, Server đánh dấu
   instance này là **hết hạn lease (expired)** và loại khỏi danh sách registry đang hoạt
   động (chuyển sang trạng thái sẽ bị eviction ở lần quét dọn kế tiếp — eviction task chạy
   mỗi 60 giây theo mặc định).
3. Vì vậy, **thời gian tối đa để Eureka Server loại bỏ instance chết** thường rơi vào
   khoảng **90–120 giây** (90s chờ hết hạn lease + tối đa ~60s cho tới lần chạy eviction
   task tiếp theo, tùy thời điểm rơi vào chu kỳ nào).
4. Sau khi Server loại instance khỏi registry, `order-service` vẫn cần đợi thêm tới chu kỳ
   `registry-fetch-interval-seconds` (mặc định 30 giây) tiếp theo để **cache cục bộ** của
   nó được cập nhật danh sách mới nhất.
5. **Trong khoảng thời gian instance đã crash nhưng chưa bị loại khỏi cache của
   order-service**, các request gọi đến instance chết đó sẽ bị timeout/connection-refused.
   Đây là lý do trong thực tế nên kết hợp thêm:
   - **Retry + timeout ngắn** ở tầng client (Resilience4j/Spring Retry) để tự động fail
     sang instance khác nhanh hơn là chờ Eureka phát hiện.
   - **Circuit breaker** để cô lập tạm thời instance lỗi, tránh dồn request thất bại liên
     tục.

### Tổng thời gian ước tính (cấu hình mặc định)

```
Tối thiểu: 90s (hết hạn lease)
Tối đa:    90s + ~60s (chu kỳ eviction) + 30s (client fetch registry) ≈ 180s (3 phút)
```

> Có thể rút ngắn bằng cách giảm `lease-expiration-duration-in-seconds` (ví dụ 15–20s) và
> `registry-fetch-interval-seconds`, nhưng cần cân nhắc đánh đổi: giảm quá thấp làm tăng
> tải cho Eureka Server và dễ loại nhầm instance chỉ đang tạm chậm (network jitter, GC
> pause) chứ chưa thực sự chết — gây "false eviction".

---

## 5. Khuyến nghị cho `order-service`: tra cứu động thay vì địa chỉ cứng

`order-service` **không nên** hard-code địa chỉ IP:port của `restaurant-service` trong
`application.yml` hay trong code. Thay vào đó:

### 5.1. Dùng `@LoadBalanced RestTemplate` hoặc `WebClient` + tên service logic

```java
@Bean
@LoadBalanced
public RestTemplate restTemplate() {
    return new RestTemplate();
}
```

```java
// Gọi qua tên service đã đăng ký trên Eureka, KHÔNG dùng IP:port cứng
restTemplate.getForObject("http://restaurant-service/api/menu/{id}", MenuDto.class, id);
```

Spring Cloud LoadBalancer sẽ tự động:
- Tra cứu danh sách instance mới nhất của `restaurant-service` từ cache registry
  (được đồng bộ định kỳ theo `registry-fetch-interval-seconds`).
- Áp dụng thuật toán load balancing (mặc định Round Robin) để phân phối request đều lên
  tất cả các instance đang khỏe mạnh (UP).

### 5.2. Hoặc dùng OpenFeign (khuyến nghị cho hệ thống lớn, dễ maintain hơn)

```java
@FeignClient(name = "restaurant-service")
public interface RestaurantClient {
    @GetMapping("/api/menu/{id}")
    MenuDto getMenu(@PathVariable("id") Long id);
}
```

Feign tự tích hợp sẵn Spring Cloud LoadBalancer, không cần tự quản lý danh sách instance.

### 5.3. Cấu hình bổ sung phía `order-service` để đảm bảo dữ liệu discovery luôn mới

```yaml
eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/
    registry-fetch-interval-seconds: 5   # rút ngắn chu kỳ đồng bộ registry (mặc định 30s)
```

> Không nên đặt quá thấp (ví dụ 1–2s) trong môi trường production nhiều service vì sẽ tạo
> áp lực lớn lên Eureka Server. 5–10s là mức hợp lý cho hệ thống vừa và nhỏ.

### 5.4. Kết hợp thêm Resilience4j để giảm phụ thuộc vào tốc độ phát hiện của Eureka

```yaml
resilience4j:
  circuitbreaker:
    instances:
      restaurantService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
  retry:
    instances:
      restaurantService:
        max-attempts: 2
        wait-duration: 200ms
```

---

## 6. Kết luận — Nguyên nhân gốc rễ (Root Cause)

| # | Vấn đề | Mức độ ảnh hưởng | Đã khắc phục |
|---|---|---|---|
| 1 | Thiếu dấu `/` cuối `defaultZone` | **Nghiêm trọng** — nguyên nhân trực tiếp khiến instance không đăng ký được, gây ra đúng triệu chứng "chỉ thấy 1 instance" | ✅ (xem `restaurant-service-application-fixed.yml`) |
| 2 | `spring.application.name` sai case | Trung bình — không gây lỗi chức năng ngay nhưng gây khó khăn vận hành, bảo trì, tích hợp về sau | ✅ |
| 3 | Thiếu khai báo rõ `register-with-eureka`, `fetch-registry` | Thấp — mặc định đúng nhưng thiếu tường minh, dễ gây nhầm lẫn khi đọc code | ✅ |
| 4 | `order-service` không có cơ chế discovery động / retry / circuit breaker | Trung bình — làm chậm khả năng phục hồi khi 1 instance chết đột ngột | ✅ (khuyến nghị) |

Sau khi sửa đúng `defaultZone`, đồng bộ hóa `spring.application.name` theo kebab-case, và
đảm bảo `order-service` tra cứu qua tên logic service (không hard-code địa chỉ), hệ thống
sẽ tự động nhận diện đầy đủ cả 4 instance của `restaurant-service` và load balancing đúng
theo thiết kế khi scale.
