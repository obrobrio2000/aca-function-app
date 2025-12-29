package com.FunctionAppFileProcessor;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.systemevents.StorageBlobCreatedEventData;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.ServiceBusQueueTrigger;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Azure Function triggered by Service Bus to process blobs.
 * Listens to Service Bus queue for Event Grid blob created events,
 * downloads the blob, processes it, and uploads results.
 */
public class ServiceBusFunction {
    
    private static final String SOURCE_ENDPOINT_ENV = "FileStorageAccountEndpoint";
    private static final String DEST_ENDPOINT_ENV = "DestinationStorageAccountEndpoint";
    private static final String EVENT_HUB_NAMESPACE_ENV = "EventHubNamespace";
    private static final String EVENT_HUB_NAME_ENV = "EventHubName";
    private static final String DESTINATION_CONTAINER = "filtered-csv";
    private static final String AZURE_CLIENT_ID_ENV = "AZURE_CLIENT_ID";
    
    private BlobServiceClient sourceBlobServiceClient;
    private BlobServiceClient destinationBlobServiceClient;
    private EventHubProducerClient eventHubProducerClient;
    private final TokenCredential credential;
    
    public ServiceBusFunction() {
        // Get the managed identity client ID from environment variable
        String managedIdentityClientId = System.getenv(AZURE_CLIENT_ID_ENV);
        
        // Initialize Azure credential
        // Use DefaultAzureCredential with managedIdentityClientId for User Assigned Managed Identity
        // This maintains the credential chain (CLI, VS Code, etc.) for local development
        // while also supporting User Assigned Managed Identity in Azure
        DefaultAzureCredentialBuilder credentialBuilder = new DefaultAzureCredentialBuilder();
        if (managedIdentityClientId != null && !managedIdentityClientId.isEmpty()) {
            credentialBuilder.managedIdentityClientId(managedIdentityClientId);
        }
        this.credential = credentialBuilder.build();
        
        // Initialize blob service clients
        String sourceEndpoint = System.getenv(SOURCE_ENDPOINT_ENV);
        String destEndpoint = System.getenv(DEST_ENDPOINT_ENV);
        
        if (sourceEndpoint != null && !sourceEndpoint.isEmpty()) {
            this.sourceBlobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(sourceEndpoint)
                    .credential(credential)
                    .buildClient();
        }
        
        if (destEndpoint != null && !destEndpoint.isEmpty()) {
            this.destinationBlobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(destEndpoint)
                    .credential(credential)
                    .buildClient();
        }
        
        // Initialize Event Hub producer client
        String eventHubNamespace = System.getenv(EVENT_HUB_NAMESPACE_ENV);
        String eventHubName = System.getenv(EVENT_HUB_NAME_ENV);
        
        if (eventHubNamespace != null && !eventHubNamespace.isEmpty() && 
            eventHubName != null && !eventHubName.isEmpty()) {
            this.eventHubProducerClient = new EventHubClientBuilder()
                    .fullyQualifiedNamespace(eventHubNamespace)
                    .eventHubName(eventHubName)
                    .credential(credential)
                    .buildProducerClient();
        }
    }

    /**
     * Service Bus Queue trigger function that processes Event Grid blob created events
     */
    @FunctionName("ServiceBusFunction")
    public void run(
            @ServiceBusQueueTrigger(
                name = "message",
                queueName = "queue-blob-created",
                connection = "ConnToServiceBusFile"
            ) String message,
            final ExecutionContext context
    ) {
        Logger logger = context.getLogger();
        logger.info("Message ID: " + context.getInvocationId());
        logger.info("Message Body: " + message);
        
        try {
            
            // Parse Event Grid event from Service Bus message
            EventGridEvent eventGridEvent = EventGridEvent.fromString(message).get(0);
            
            logger.info("Event Type: " + eventGridEvent.getEventType());
            logger.info("Subject: " + eventGridEvent.getSubject());
            
            // Check if it's a blob created event
            if ("Microsoft.Storage.BlobCreated".equals(eventGridEvent.getEventType())) {
                // Deserialize event data
                StorageBlobCreatedEventData blobCreatedData = 
                    eventGridEvent.getData().toObject(StorageBlobCreatedEventData.class);
                
                if (blobCreatedData == null) {
                    logger.warning("Failed to deserialize BlobCreated event data");
                    return;
                }
                
                logger.info("Blob URL: " + blobCreatedData.getUrl());
                logger.info("Blob API: " + blobCreatedData.getApi());
                logger.info("Content Type: " + blobCreatedData.getContentType());
                logger.info("Content Length: " + blobCreatedData.getContentLength());
                
                // Filtra solo gli eventi SftpCommit per evitare elaborazioni duplicate
                // SftpCreate viene generato all'inizio del caricamento (file potenzialmente incompleto)
                // SftpCommit viene generato quando il file è completamente caricato
                if (!"SftpCommit".equals(blobCreatedData.getApi())) {
                    logger.info("Ignoring event with API: " + blobCreatedData.getApi() + " - only processing SftpCommit");
                    return;
                }
                
                // Extract container and blob name from URL
                URI blobUri = URI.create(blobCreatedData.getUrl());
                String path = blobUri.getPath().substring(1); // Remove leading '/'
                String[] pathSegments = path.split("/", 2);
                
                if (pathSegments.length >= 2) {
                    String containerName = pathSegments[0];
                    String blobName = pathSegments[1];
                    
                    logger.info(String.format("Container: %s, Blob: %s", containerName, blobName));
                    
                    // Process the blob
                    processBlobAsync(containerName, blobName, logger);
                    
                } else {
                    logger.warning("Unable to parse blob path from URL: " + blobCreatedData.getUrl());
                }
            } else {
                logger.warning("Received non-BlobCreated event: " + eventGridEvent.getEventType());
            }
            
            logger.info("Successfully processed blob and completed message");
            
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Error processing blob from Service Bus message", ex);
            throw new RuntimeException("Error processing message", ex);
        }
    }
    
    /**
     * Process blob: download, process, and upload result
     */
    private void processBlobAsync(String containerName, String blobName, Logger logger) {
        try {
            // Get source blob client
            BlobContainerClient sourceContainerClient = 
                sourceBlobServiceClient.getBlobContainerClient(containerName);
            BlobClient sourceBlobClient = sourceContainerClient.getBlobClient(blobName);
            
            // Check if blob exists
            if (!sourceBlobClient.exists()) {
                logger.warning(String.format("Source blob does not exist: %s/%s", containerName, blobName));
                return;
            }
            
            // Get blob properties
            BlobProperties properties = sourceBlobClient.getProperties();
            logger.info("Processing Blob. Size: " + properties.getBlobSize() + " bytes");

            // ====================================================================
            // ESEMPIO 1: Lettura file come stream (senza scaricarlo localmente)
            // ====================================================================
            try (InputStream sourceStream = sourceBlobClient.openInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(sourceStream, StandardCharsets.UTF_8))) {
                // TODO: Aggiungere/modificare la logica di lettura del file
            }

            // ====================================================================
            // ESEMPIO 2: Creazione nuovo file di output su storage account diverso
            // ====================================================================
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String destinationBlobName = String.format("%s_new_%s", timestamp, blobName);
            BlobContainerClient destContainerClient = 
                destinationBlobServiceClient.getBlobContainerClient(DESTINATION_CONTAINER);
            
            // Create destination container if it doesn't exist
            destContainerClient.createIfNotExists();
            
            logger.info(String.format("Destination blob: %s/%s", DESTINATION_CONTAINER, destinationBlobName));
            
            BlobClient destBlobClient = destContainerClient.getBlobClient(destinationBlobName);
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                 OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                // TODO: Aggiungere/modificare la logica di scrittura del file
            }

            // ====================================================================
            // ESEMPIO 3: Invio evento JSON su Azure Event Hub
            // ====================================================================
            if (eventHubProducerClient != null) {
                String jsonEvent = String.format(
                    "{\"ProcessedAt\":\"%s\",\"SourceBlobName\":\"%s\",\"Field1\":\"Foo\",\"Field2\":\"Bar\"}",
                    LocalDateTime.now().toString(),
                    blobName
                );
                
                EventDataBatch eventDataBatch = eventHubProducerClient.createBatch();
                
                if (!eventDataBatch.tryAdd(new EventData(jsonEvent))) {
                    logger.severe("Event is too large for the batch");
                } else {
                    eventHubProducerClient.send(eventDataBatch);
                    logger.info("Event sent to Event Hub: " + jsonEvent);
                }
                // TODO: Aggiungere/modificare la logica di invio evento
            } else {
                logger.warning("Event Hub producer client not initialized - skipping Event Hub send");
            }

        } catch (Exception ex) {
            logger.log(Level.SEVERE, 
                String.format("Error processing File Blob: %s/%s", containerName, blobName), ex);
            throw new RuntimeException("Error processing blob", ex);
        }
    }
}
