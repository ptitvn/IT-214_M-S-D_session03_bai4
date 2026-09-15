// Ví dụ minh họa: order-service gọi restaurant-service qua Eureka Service Discovery
// KHÔNG hard-code IP:port, luôn tra cứu tên service logic "restaurant-service"

package com.foodx.orderservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "restaurant-service") // tên phải khớp spring.application.name đã đăng ký trên Eureka
public interface RestaurantClient {

    @GetMapping("/api/menu/{id}")
    MenuDto getMenu(@PathVariable("id") Long id);
}

/*
 * Ghi chú:
 * - Spring Cloud OpenFeign tích hợp sẵn Spring Cloud LoadBalancer.
 * - Mỗi lần gọi, Feign sẽ tra cứu danh sách instance UP mới nhất của "restaurant-service"
 *   từ cache registry local (được đồng bộ theo registry-fetch-interval-seconds),
 *   rồi áp dụng round-robin để chọn 1 instance khả dụng.
 * - Nếu 1 instance bị crash và chưa kịp bị Eureka loại khỏi registry, nên kết hợp thêm
 *   Resilience4j Circuit Breaker + Retry để tự động chuyển sang instance khác nhanh hơn.
 */
