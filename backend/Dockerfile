# Image chạy trên Cloud Run, dùng chung cho các service api / worker / relay (khác nhau ở biến môi trường).
# Build: docker build -t bookstore-backend .
# Multi-stage: stage build có JDK và Maven; stage runtime chỉ gồm JRE và file jar.

# ---------- Build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Tải dependency trước khi copy mã nguồn để tận dụng layer cache.
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Chuẩn hóa xuống dòng (CRLF -> LF) và cấp quyền thực thi cho mvnw khi build từ Windows.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Test chạy ở bước CI riêng (Testcontainers cần Docker), không chạy trong docker build.
RUN ./mvnw -B -q package -DskipTests

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Chạy bằng user không có quyền root.
RUN useradd --system --uid 1001 --no-create-home app
COPY --from=build /app/target/bookstore-*.jar app.jar
USER app

# Heap tối đa 75% bộ nhớ được cấp cho container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Cloud Run truyền cổng qua biến PORT (mặc định 8080).
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
