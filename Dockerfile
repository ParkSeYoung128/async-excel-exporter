# ---------- build stage ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 의존성 레이어를 소스와 분리해 소스만 바뀌면 의존성 다운로드를 건너뛴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre
ENV TZ=Asia/Seoul

# non-root 사용자로 실행. named volume 은 첫 마운트 때 이 디렉터리의 소유권을 그대로 가져간다
RUN groupadd --system app \
    && useradd --system --gid app --no-create-home app \
    && mkdir -p /data/exports \
    && chown -R app:app /data/exports

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

USER app
EXPOSE 8080

# 힙은 컨테이너 메모리의 65%. 나머지는 Metaspace, 스레드 스택, 코드 캐시, POI 등 힙 밖 메모리 몫
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
