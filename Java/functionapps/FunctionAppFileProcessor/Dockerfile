# Use Azure Functions base image for Java 21
FROM mcr.microsoft.com/azure-functions/java:4-java21 AS base
WORKDIR /home/site/wwwroot

# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy pom.xml and download dependencies
COPY . .
RUN mvn dependency:go-offline -B
RUN mvn clean package -DskipTests

# Download Application Insights Java Agent for distributed tracing
FROM base AS agent-download
ARG APPLICATIONINSIGHTS_AGENT_VERSION=3.6.2
RUN apt-get update && apt-get install -y curl && \
    curl -L -o /tmp/applicationinsights-agent.jar \
    "https://github.com/microsoft/ApplicationInsights-Java/releases/download/${APPLICATIONINSIGHTS_AGENT_VERSION}/applicationinsights-agent-${APPLICATIONINSIGHTS_AGENT_VERSION}.jar"

# Final stage
FROM base AS final
WORKDIR /home/site/wwwroot

# Copy Application Insights agent
COPY --from=agent-download /tmp/applicationinsights-agent.jar /opt/applicationinsights-agent.jar

# Copy the built function app
COPY --from=build /build/target/azure-functions/* ./

# Set environment variables
ENV AzureWebJobsScriptRoot=/home/site/wwwroot \
    AzureFunctionsJobHost__Logging__Console__IsEnabled=true \
    FUNCTIONS_WORKER_RUNTIME=java \
    JAVA_OPTS="-javaagent:/opt/applicationinsights-agent.jar"

# Expose port (Azure Functions listens on 80 in container)
EXPOSE 80
