# 빌드 컨텍스트 = backend 저장소 루트 (libs/common-web composite build, 결정 5번 B-1)
#   docker build -f services/payment-service/Dockerfile .
# ===== 1단계: 빌드 (로컬 JDK 없이도, CI 어디서든 같은 결과) =====
FROM eclipse-temurin:21-jdk AS builder
# settings.gradle 의 includeBuild('../../libs/common-web') 상대 경로가 컨테이너 안에서도 맞도록 저장소 구조(/app/libs · /app/services/<서비스>)를 유지한다
WORKDIR /app/services/payment-service
COPY libs/common-web/settings.gradle libs/common-web/build.gradle /app/libs/common-web/
COPY services/payment-service/gradlew services/payment-service/settings.gradle services/payment-service/build.gradle services/payment-service/gradle.properties ./
COPY services/payment-service/gradle ./gradle
# 소스보다 의존성을 먼저 받아 두면, 코드만 바뀔 때 이 레이어는 캐시를 재사용한다
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null
COPY libs/common-web/src /app/libs/common-web/src
COPY services/payment-service/src ./src
# 테스트는 CI 의 test 단계에서 이미 돌았으므로 여기서는 생략
RUN ./gradlew bootJar -x test --no-daemon
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination extracted

# ===== 2단계: 실행 (JRE 만, root 아닌 사용자) =====
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 spring
WORKDIR /app
COPY --from=builder /app/services/payment-service/extracted/dependencies/ ./
COPY --from=builder /app/services/payment-service/extracted/spring-boot-loader/ ./
COPY --from=builder /app/services/payment-service/extracted/snapshot-dependencies/ ./
# 자주 바뀌는 코드 레이어는 맨 마지막
COPY --from=builder /app/services/payment-service/extracted/application/ ./
USER spring
# 컨테이너 기본 시간대는 UTC — DB · JDBC 와 맞춰 Asia/Seoul 로 고정 (컨벤션 3-5)
ENV TZ=Asia/Seoul
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8086
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
