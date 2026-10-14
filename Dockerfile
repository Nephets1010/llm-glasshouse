# syntax=docker/dockerfile:1
# 版权 2026 the llm-glasshouse authors.
# Licensed under the Apache License, Version 2.0.

# ── 构建阶段 ──────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 依赖层单独缓存：只改代码时不重新下载依赖
COPY pom.xml .
COPY glasshouse-core/pom.xml glasshouse-core/
COPY glasshouse-spring-boot-starter/pom.xml glasshouse-spring-boot-starter/
COPY glasshouse-eval/pom.xml glasshouse-eval/
COPY glasshouse-cli/pom.xml glasshouse-cli/
COPY glasshouse-demo/pom.xml glasshouse-demo/
RUN mvn -B -q dependency:go-offline || true

COPY . .
# ★ 不加 -Pfast：那个 profile 会跳过覆盖率门禁与 spotless，
#   容器镜像必须是「门禁真的过了」的产物，否则镜像可信度为零
RUN mvn -B clean package -DskipTests

# ── 运行阶段 ──────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# 非 root 运行：容器逃逸时的最小权限原则
RUN groupadd --system glasshouse && useradd --system --gid glasshouse glasshouse

COPY --from=builder /build/glasshouse-demo/target/glasshouse-demo-*.jar /app/glasshouse.jar
COPY --from=builder /build/glasshouse-cli/target/glasshouse-cli-*-cli.jar /app/glasshouse-cli.jar

# 价格表挂载点：改价格不改镜像、不重启（架构 P0-7）
RUN mkdir -p /app/prices
VOLUME ["/app/prices"]

USER glasshouse
EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -q -O- http://127.0.0.1:8080/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/glasshouse.jar"]
