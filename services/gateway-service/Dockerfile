# ===== 1단계: 빌드 (로컬 JDK 없이도, CI 어디서든 같은 결과) =====
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
# 소스보다 의존성을 먼저 받아 두면, 코드만 바뀔 때 이 레이어는 캐시를 재사용한다
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null
COPY src ./src
# 테스트는 CI 의 test 단계에서 이미 돌았으므로 여기서는 생략
RUN ./gradlew bootJar -x test --no-daemon
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination extracted

# ===== 2단계: 실행 (JRE 만, root 아닌 사용자) =====
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 spring
WORKDIR /app
COPY --from=builder /app/extracted/dependencies/ ./
COPY --from=builder /app/extracted/spring-boot-loader/ ./
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
# 자주 바뀌는 코드 레이어는 맨 마지막
COPY --from=builder /app/extracted/application/ ./
USER spring
# 컨테이너 기본 시간대는 UTC — DB · JDBC 와 맞춰 Asia/Seoul 로 고정 (컨벤션 3-5)
ENV TZ=Asia/Seoul
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
