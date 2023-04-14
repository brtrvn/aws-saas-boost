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
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

public class Auth0Setup implements RequestHandler<Map<String, Object>, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Auth0Setup.class);
    private static final String RESPONSE_DATA_KEY_WEB_APP_CLIENT_ID = "AdminWebAppClientId";
    private static final String RESPONSE_DATA_KEY_WEB_APP_CLIENT_NAME = "AdminWebAppClientName";
    private static final String RESPONSE_DATA_KEY_API_APP_CLIENT_ID = "ApiAppClientId";
    private static final String RESPONSE_DATA_KEY_API_APP_CLIENT_NAME = "ApiAppClientName";
    private static final String RESPONSE_DATA_KEY_API_APP_CLIENT_SECRET = "ApiAppClientSecret";
    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final SecretsManagerClient secrets;

    public Auth0Setup() {
        LOGGER.info("Version Info: {}", Utils.version(this.getClass()));
        secrets = Utils.sdkClient(SecretsManagerClient.builder(), SecretsManagerClient.SERVICE_NAME);
    }

    @Override
    public Object handleRequest(Map<String, Object> event, Context context) {
        Utils.logRequestEvent(event);

        final String requestType = (String) event.get("RequestType");
        final Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
        final String adminUserSecretId = (String) resourceProperties.get("AdminUserCredentials");
        final String auth0SecretId = (String) resourceProperties.get("Auth0Credentials");
        final String connectionName = (String) resourceProperties.get("ConnectionName");
        String adminWebAppUrl = (String) resourceProperties.get("AdminWebAppUrl");
        final String redirectUriPattern = (!adminWebAppUrl.endsWith("/*")) ? adminWebAppUrl + "/*" : adminWebAppUrl;

        ExecutorService service = Executors.newSingleThreadExecutor();
        Map<String, Object> responseData = new HashMap<>();
        try {
            Runnable r = () -> {
                if ("Create".equalsIgnoreCase(requestType)) {
                    LOGGER.info("CREATE");
                    // TODO Create the necessary Auth0 resources
                    // TODO 1. Create an Auth0 Connection for this SaaS Boost env
                    // TODO 2. Create a "public" OAuth app client for the admin web app to use with PKCE
                    // TODO 3. Create a "private" OAuth app client with secret for client credentials API access
                    // TODO 4. Make an "admin" group to put System Users into
                    // TODO 5. Create the initial SaaS Boost admin user as a member of the "admin" group
                    try {
                        LOGGER.info("Fetching SaaS Boost admin user credentials from Secrets Manager");
                        GetSecretValueResponse adminUserSecretValue = secrets.getSecretValue(request -> request
                                .secretId(adminUserSecretId)
                        );
                        final Map<String, String> adminUserCredentials = Utils.fromJson(
                                adminUserSecretValue.secretString(), LinkedHashMap.class);

                        LOGGER.info("Fetching Auth0 admin credentials from Secrets Manager");
                        GetSecretValueResponse auth0SecretValue = secrets.getSecretValue(request -> request
                                .secretId(auth0SecretId)
                        );
                        final Map<String, String> auth0Credentials = Utils.fromJson(
                                auth0SecretValue.secretString(), LinkedHashMap.class);

                        responseData.putAll(Map.of(
                                RESPONSE_DATA_KEY_API_APP_CLIENT_ID, Utils.randomString(20),
                                RESPONSE_DATA_KEY_API_APP_CLIENT_NAME, connectionName + "-api-client",
                                RESPONSE_DATA_KEY_API_APP_CLIENT_SECRET, "",
                                RESPONSE_DATA_KEY_WEB_APP_CLIENT_ID, Utils.randomString(20),
                                RESPONSE_DATA_KEY_WEB_APP_CLIENT_NAME,  connectionName + "-admin-webapp-client"
                        ));
                        // We're returning sensitive data, so be sure to use NoEcho = true
                        // https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/crpg-ref-responses.html
                        CloudFormationResponse.send(event, context, "SUCCESS", responseData, true);
                    } catch (SdkServiceException secretsManagerError) {
                        LOGGER.error("Secrets Manager error {}", secretsManagerError.getMessage());
                        LOGGER.error(Utils.getFullStackTrace(secretsManagerError));
                        responseData.put("Reason", secretsManagerError.getMessage());
                        CloudFormationResponse.send(event, context, "FAILED", responseData);
                    } catch (Exception e) {
                        LOGGER.error(Utils.getFullStackTrace(e));
                        responseData.put("Reason", e.getMessage());
                        CloudFormationResponse.send(event, context, "FAILED", responseData);
                    }
                } else if ("Update".equalsIgnoreCase(requestType)) {
                    LOGGER.info("UPDATE");
                    // TODO What does it mean if we update the stack?
                    CloudFormationResponse.send(event, context, "SUCCESS", responseData);
                } else if ("Delete".equalsIgnoreCase(requestType)) {
                    LOGGER.info("DELETE");
                    // TODO Should we delete the Auth0 resources when we delete the stack?
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
            LOGGER.error("FAILED unexpected error or request timed out " + e.getMessage());
            LOGGER.error(Utils.getFullStackTrace(e));
            responseData.put("Reason", e.getMessage());
            CloudFormationResponse.send(event, context, "FAILED", responseData);
        } finally {
            service.shutdown();
        }
        return null;
    }
}
