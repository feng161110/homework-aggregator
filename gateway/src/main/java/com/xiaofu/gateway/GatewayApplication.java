package com.xiaofu.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一入口（Spring Cloud Gateway）。
 *
 * <p>只做转发，不含业务逻辑：所有路由规则都写在 resources/application.yml 里，
 * 上线时改转发目标只要换环境变量或配置，不必重新编译代码。
 *
 * <p>路由一览（配置见 application.yml，按声明顺序匹配）：
 * <ul>
 *   <li>/api/zhs/**、/api/ykt/**、/api/xxt/** → 智慧树 / 雨课堂 / 学习通服务（2001 / 2002 / 2003）</li>
 *   <li>/api/**、/uploads/**                  → Java 后端（默认 8081）</li>
 * </ul>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
