# ---- build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac --release 17 -encoding UTF-8 -d out src/com/spos/editor/*.java

# ---- run stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 app && mkdir -p /app/data && chown app /app/data
COPY --from=build /app/out ./out
COPY static ./static
ENV DATA_FILE=/app/data/document.db
USER app
EXPOSE 8080
# The server reads the PORT variable that Render / Railway / Fly provide (default 8080).
CMD ["java", "-cp", "out", "com.spos.editor.Server"]
