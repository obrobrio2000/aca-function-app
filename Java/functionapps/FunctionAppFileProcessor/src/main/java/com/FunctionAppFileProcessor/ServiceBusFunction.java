package com.FunctionAppFileProcessor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventgrid.EventGridEvent;
import com.azure.messaging.eventgrid.systemevents.StorageBlobCreatedEventData;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobProperties;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.ServiceBusQueueTrigger;

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
    private static final String DESTINATION_CONTAINER_ENV = "DestinationContainerName";
    private static final String DESTINATION_BLOB_PREFIX_ENV = "DestinationBlobPrefix";
    private static final String PROCESS_ONLY_COMMITTED_ENV = "ProcessOnlyCommittedFiles";
    private static final String ACCEPTED_API_TYPES_ENV = "AcceptedBlobApiTypes";
    private static final String AZURE_CLIENT_ID_ENV = "AZURE_CLIENT_ID";
    
    // Static clients following Azure SDK best practices for connection reuse
    // See: https://learn.microsoft.com/en-us/azure/azure-functions/manage-connections
    private static final TokenCredential credential;
    private static final BlobServiceClient sourceBlobServiceClient;
    private static final BlobServiceClient destinationBlobServiceClient;
    private static final EventHubProducerClient eventHubProducerClient;
    
    // Static initializer block - executed once when the class is loaded
    // This ensures thread-safe singleton initialization of all Azure SDK clients
    static {
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
        credential = credentialBuilder.build();
        
        // Initialize blob service clients
        String sourceEndpoint = System.getenv(SOURCE_ENDPOINT_ENV);
        String destEndpoint = System.getenv(DEST_ENDPOINT_ENV);
        
        if (sourceEndpoint != null && !sourceEndpoint.isEmpty()) {
            sourceBlobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(sourceEndpoint)
                    .credential(credential)
                    .buildClient();
        } else {
            sourceBlobServiceClient = null;
        }
        
        if (destEndpoint != null && !destEndpoint.isEmpty()) {
            destinationBlobServiceClient = new BlobServiceClientBuilder()
                    .endpoint(destEndpoint)
                    .credential(credential)
                    .buildClient();
        } else {
            destinationBlobServiceClient = null;
        }
        
        // Initialize Event Hub producer client
        String eventHubNamespace = System.getenv(EVENT_HUB_NAMESPACE_ENV);
        String eventHubName = System.getenv(EVENT_HUB_NAME_ENV);
        
        if (eventHubNamespace != null && !eventHubNamespace.isEmpty() && 
            eventHubName != null && !eventHubName.isEmpty()) {
            eventHubProducerClient = new EventHubClientBuilder()
                    .fullyQualifiedNamespace(eventHubNamespace)
                    .eventHubName(eventHubName)
                    .credential(credential)
                    .buildProducerClient();
        } else {
            eventHubProducerClient = null;
        }
    }
    
    public ServiceBusFunction() {
        // No-op constructor - all clients are initialized in the static block
    }

    /**
     * Service Bus Queue trigger function that processes Event Grid blob created events
     */
    @FunctionName("ServiceBusFunction")
    public void run(
            @ServiceBusQueueTrigger(
                name = "message",
                queueName = "%ConnToServiceBusFile__queueName%",
                connection = "ConnToServiceBusFile"
            ) String message,
            final ExecutionContext context
    ) {
        Logger logger = context.getLogger();
        logger.info(String.format("Message ID: %s", context.getInvocationId()));
        logger.info(String.format("Message Body: %s", message));
        
        try {
            
            // Parse Event Grid event from Service Bus message
            EventGridEvent eventGridEvent = EventGridEvent.fromString(message).get(0);
            
            logger.info(String.format("Event Type: %s", eventGridEvent.getEventType()));
            logger.info(String.format("Subject: %s", eventGridEvent.getSubject()));
            
            // Check if it's a blob created event
            if ("Microsoft.Storage.BlobCreated".equals(eventGridEvent.getEventType())) {
                // Deserialize event data
                StorageBlobCreatedEventData blobCreatedData = 
                    eventGridEvent.getData().toObject(StorageBlobCreatedEventData.class);
                
                if (blobCreatedData == null) {
                    logger.warning("Failed to deserialize BlobCreated event data");
                    return;
                }
                
                logger.info(String.format("Blob URL: %s", blobCreatedData.getUrl()));
                logger.info(String.format("Blob API: %s", blobCreatedData.getApi()));
                logger.info(String.format("Content Type: %s", blobCreatedData.getContentType()));
                logger.info(String.format("Content Length: %s", blobCreatedData.getContentLength()));
                
                // Filtra gli eventi in base ai tipi di API configurati
                String processOnlyCommitted = System.getenv(PROCESS_ONLY_COMMITTED_ENV);
                boolean shouldFilterApi = processOnlyCommitted == null || Boolean.parseBoolean(processOnlyCommitted);
                
                if (shouldFilterApi) {
                    String acceptedApiTypesStr = System.getenv(ACCEPTED_API_TYPES_ENV);
                    String[] acceptedApiTypes = acceptedApiTypesStr != null ? 
                        acceptedApiTypesStr.split(",") : new String[]{"SftpCommit"};
                    
                    boolean isAccepted = false;
                    for (String apiType : acceptedApiTypes) {
                        if (apiType.trim().equals(blobCreatedData.getApi())) {
                            isAccepted = true;
                            break;
                        }
                    }
                    
                    if (!isAccepted) {
                        logger.info(String.format("Ignoring event with API: %s - only processing: %s", 
                            blobCreatedData.getApi(), String.join(", ", acceptedApiTypes)));
                        return;
                    }
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
                    logger.warning(String.format("Unable to parse blob path from URL: %s", blobCreatedData.getUrl()));
                }
            } else {
                logger.warning(String.format("Received non-BlobCreated event: %s", eventGridEvent.getEventType()));
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
            logger.info(String.format("Processing Blob. Size: %s bytes", properties.getBlobSize()));

            // ====================================================================
            // ESEMPIO 1: Lettura file come stream (senza scaricarlo localmente)
            // ====================================================================
            @SuppressWarnings("unused")
            InputStream sourceStream = sourceBlobClient.openInputStream();
            try {
                // TODO: Aggiungere/modificare la logica di lettura del file
                // BufferedReader reader = new BufferedReader(new InputStreamReader(sourceStream, StandardCharsets.UTF_8));
            } finally {
                if (sourceStream != null) {
                    sourceStream.close();
                }
            }

            // ====================================================================
            // ESEMPIO 2: Creazione nuovo file di output su storage account diverso
            // ====================================================================
            String destinationContainerName = System.getenv(DESTINATION_CONTAINER_ENV);
            if (destinationContainerName == null || destinationContainerName.isEmpty()) {
                destinationContainerName = "filtered-csv";
            }
            
            String destinationBlobPrefix = System.getenv(DESTINATION_BLOB_PREFIX_ENV);
            if (destinationBlobPrefix == null || destinationBlobPrefix.isEmpty()) {
                destinationBlobPrefix = "new_";
            }
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String destinationBlobName = String.format("%s_%s%s", timestamp, destinationBlobPrefix, blobName);
            BlobContainerClient destContainerClient = 
                destinationBlobServiceClient.getBlobContainerClient(destinationContainerName);
            
            // Create destination container if it doesn't exist
            destContainerClient.createIfNotExists();
            
            logger.info(String.format("Destination blob: %s/%s", destinationContainerName, destinationBlobName));
            
            // BlobClient destBlobClient = destContainerClient.getBlobClient(destinationBlobName);
            @SuppressWarnings("unused")
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                // TODO: Aggiungere/modificare la logica di scrittura del file
                // OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
            } finally {
                outputStream.close();
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
                    logger.info(String.format("Event sent to Event Hub: %s", jsonEvent));
                }
                // TODO: Aggiungere/modificare la logica di invio evento
            } else {
                logger.warning("Event Hub producer client not initialized - skipping Event Hub send");
            }

        } catch (java.io.IOException ex) {
            logger.log(Level.SEVERE, 
                String.format("IO error processing File Blob: %s/%s", containerName, blobName), ex);
            throw new RuntimeException("Error processing blob", ex);
        } catch (RuntimeException ex) {
            logger.log(Level.SEVERE, 
                String.format("Runtime error processing File Blob: %s/%s", containerName, blobName), ex);
            throw ex;
        }
    }
}
