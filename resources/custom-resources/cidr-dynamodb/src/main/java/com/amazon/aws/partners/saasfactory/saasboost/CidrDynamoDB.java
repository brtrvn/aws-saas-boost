/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazon.aws.partners.saasfactory.saasboost;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.concurrent.*;

public class CidrDynamoDB implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CidrDynamoDB.class);
    private final DynamoDbClient ddb;

    public CidrDynamoDB() {
        LOGGER.info("Version Info: {}", Utils.version(this.getClass()));
        this.ddb = Utils.sdkClient(DynamoDbClient.builder(), DynamoDbClient.SERVICE_NAME);
    }

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        Utils.logRequestEvent(event);

        final String requestType = (String) event.get("RequestType");
        Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        final String table = (String) resourceProperties.get("Table");

        ExecutorService service = Executors.newSingleThreadExecutor();
        Map<String, Object> responseData = new HashMap<>();
        try {
            Runnable r = () -> {
                if ("Create".equalsIgnoreCase(requestType) || "Update".equalsIgnoreCase(requestType)) {
                    LOGGER.info("CREATE or UPDATE");
                    try {
                        ScanResponse scan = ddb.scan(request -> request.tableName(table));
                        // ScanResponse::hasItems will return true even with an empty list
                        if (scan.hasItems() && !scan.items().isEmpty()) {
                            LOGGER.info("CIDR table {} is already populated with {} items", table, scan.count());
                        } else {
                            LOGGER.info("Populating CIDR table");
                            LOGGER.debug("Increasing WCU for data import");
                            ddb.updateTable(request -> request
                                    .tableName(table)
                                    .provisionedThroughput(
                                            ProvisionedThroughput.builder()
                                                    .writeCapacityUnits(2000L)
                                                    .readCapacityUnits(10L)
                                                    .build()
                                    )
                            );
                            ddb.waiter().waitUntilTableExists(request -> request.tableName(table));
                            LOGGER.debug("Updated provisioned capacity");

                            List<List<WriteRequest>> batches = generateBatches();
                            final int maxRetries = 10;
                            //int batchWrite = 0;
                            //final long startTimeMillis = System.currentTimeMillis();
                            for (List<WriteRequest> batch : batches) {
                                try {
                                    long batchTimeMillis = System.currentTimeMillis();
                                    BatchWriteItemResponse writeResponse = ddb.batchWriteItem(request -> request
                                            .requestItems(Map.of(table, batch))
                                    );
                                    //long batchTotalTimeMillis = System.currentTimeMillis() - batchTimeMillis;
                                    //long cumulativeTimeMillis = System.currentTimeMillis() - startTimeMillis;
                                    //LOGGER.debug("BatchWriteItem {}/{} {} {}", ++batchWrite, batches.size(),
                                    //        batchTotalTimeMillis, cumulativeTimeMillis);

                                    Map<String, List<WriteRequest>> unprocessed = writeResponse.unprocessedItems();
                                    int retries = 0;
                                    while (!unprocessed.isEmpty() && retries < maxRetries) {
                                        LOGGER.warn("{} Unprocessed items in batch!", unprocessed.values().size());
                                        try {
                                            Thread.sleep((long) (Math.pow(2, ++retries) * 10));
                                        } catch (InterruptedException cantSleep) {
                                            LOGGER.error("Unable to pause thread!");
                                            Thread.currentThread().interrupt();
                                        }
                                        BatchWriteItemResponse unprocessedResponse = ddb.batchWriteItem(
                                                BatchWriteItemRequest.builder().requestItems(unprocessed).build());
                                        if (unprocessedResponse.hasUnprocessedItems()) {
                                            unprocessed = unprocessedResponse.unprocessedItems();
                                        }
                                    }
                                } catch (DynamoDbException e) {
                                    LOGGER.error(Utils.getFullStackTrace(e));
                                    responseData.put("Reason", e.awsErrorDetails().errorMessage());
                                    CloudFormationResponse.send(event, context, "FAILED", responseData);
                                }
                            }
                            LOGGER.debug("Resetting provisioned capacity");
                            ddb.updateTable(request -> request
                                    .tableName(table)
                                    .provisionedThroughput(
                                            ProvisionedThroughput.builder()
                                                    .writeCapacityUnits(5L)
                                                    .readCapacityUnits(10L)
                                                    .build()
                                    )
                            );
                        }
                        CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                    } catch (DynamoDbException e) {
                        LOGGER.error(Utils.getFullStackTrace(e));
                        responseData.put("Reason", e.awsErrorDetails().errorMessage());
                        CloudFormationResponse.send(event, context, "FAILED", responseData);
                    }
                } else if ("Delete".equalsIgnoreCase(requestType)) {
                    LOGGER.info("DELETE");
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else {
                    LOGGER.error("FAILED unknown requestType " + requestType);
                    responseData.put("Reason", "Unknown RequestType " + requestType);
                    CloudFormationResponse.send(event, context, "FAILED", responseData);
                }
            };
            Future<?> f = service.submit(r);
            f.get(context.getRemainingTimeInMillis() - 1000, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | InterruptedException | ExecutionException e) {
            // Timed out
            LOGGER.error("FAILED unexpected error or request timed out", e);
            String stackTrace = Utils.getFullStackTrace(e);
            LOGGER.error(stackTrace);
            responseData.put("Reason", stackTrace);
            CloudFormationResponse.send(event, context, "FAILED", responseData);
        } finally {
            service.shutdown();
        }
        return null;
    }

    protected static List<List<WriteRequest>> generateBatches() {
        // DynamoDB limits batch writes to 25 items per batch
        final int batchWriteItemLimit = 25;

        // IPv4
        final long maxOctet = Math.round(Math.pow(2, 8));

        // Breaking apart a /16 network into /21 per tenant which
        // gives you 32 blocks per octet (10.0, 10.1, 10.2, 10.3 etc...)
        final long maxVpcs = maxOctet * (Math.round(Math.pow(2, 16) / Math.pow(2, 11)));

        // You can only attach 5000 VPCs per Transit Gateway
        // We'll allocate 1/2 of the 8192 total VPCs to one and 1/2 to another
        final long vpcsPerTransitGateway = maxVpcs / 2;

        int vpc = 0;
        int octet = -1;
        List<List<WriteRequest>> batches = new ArrayList<>();
        List<WriteRequest> batch = new ArrayList<>();
        while (vpc <= maxVpcs) {
            octet++;
            for (int slash21 = 0; slash21 < maxOctet && vpc <= maxVpcs; slash21 += 8) {
                if (batch.size() == batchWriteItemLimit || vpc == maxVpcs) {
                    batches.add(new ArrayList<>(batch)); // shallow copy is ok here
                    batch.clear(); // clear out our working batch so we can fill it up again to the limit
                }
                vpc++;
                String cidr = String.format("10.%d.%d.0", octet, slash21);
                String transitGateway = vpc <= vpcsPerTransitGateway ? "A" : "B";
                WriteRequest putRequest = WriteRequest.builder()
                        .putRequest(PutRequest.builder()
                                .item(Map.of(
                                        "cidr_block", AttributeValue.builder().s(cidr).build(),
                                        "transit_gateway", AttributeValue.builder().s(transitGateway).build()
                                ))
                                .build())
                        .build();
                batch.add(putRequest);
            }
        }
        return batches;
    }
}