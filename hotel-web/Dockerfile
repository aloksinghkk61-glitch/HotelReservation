# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY *.java ./
RUN javac -d out *.java

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY public ./public
ENV PORT=8080 \
    DATA_DIR=/data \
    PUBLIC_DIR=/app/public
VOLUME /data
EXPOSE 8080
CMD ["java", "-cp", "out", "HotelWeb"]
