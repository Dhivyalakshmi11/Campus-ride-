FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY CampusRideServer.java .
COPY public/ ./public/
RUN javac --add-modules jdk.httpserver CampusRideServer.java
ENV PORT=10000
EXPOSE 10000
CMD ["java", "--add-modules", "jdk.httpserver", "CampusRideServer"]
