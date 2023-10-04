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
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class AppConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppConfigService.class);
    private static final Map<String, String> CORS = Map.of("Access-Control-Allow-Origin", "*");
    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String API_APP_CLIENT = System.getenv("API_APP_CLIENT");
    private static final String APP_CONFIG_TABLE = System.getenv("APP_CONFIG_TABLE");
    private final AppConfigDataAccessLayer dal;
    private final EventBridgeClient eventBridge;
    private final S3Presigner presigner;

    public AppConfigService() {
        this(new DefaultDependencyFactory());
    }
    
    // Facilitates testing by being able to mock out AWS SDK dependencies
    public AppConfigService(AppConfigServiceDependencyFactory init) {
        if (Utils.isBlank(AWS_REGION)) {
            throw new IllegalStateException("Missing environment variable AWS_REGION");
        }
        if (Utils.isBlank(APP_CONFIG_TABLE)) {
            throw new IllegalStateException("Missing environment variable APP_CONFIG_TABLE");
        }
        LOGGER.info("Version Info: {}", Utils.version(this.getClass()));
        this.dal = init.dal();
        this.eventBridge = init.eventBridge();
        this.presigner = init.s3Presigner();
    }

    public APIGatewayProxyResponseEvent options(APIGatewayProxyRequestEvent event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsService::configOptions");
        //Utils.logRequestEvent(event);

        Map<String, Object> options = new HashMap<>();
        options.put("osOptions", Arrays.stream(OperatingSystem.values())
                .collect(
                        Collectors.toMap(OperatingSystem::name, OperatingSystem::getDescription)
                ));
        options.put("dbOptions", dal.rdsOptions());
        options.put("acmOptions", dal.acmCertificateOptions());
        options.put("hostedZoneOptions", dal.hostedZoneOptions());

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent()
                .withStatusCode(HttpURLConnection.HTTP_OK)
                .withHeaders(CORS)
                .withBody(Utils.toJson(options));

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsService::configOptions exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent getAppConfig(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsService::getAppConfig");
        //Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;

        AppConfig appConfig = dal.getAppConfig();
        response = new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(CORS)
                .withBody(Utils.toJson(appConfig));

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsService::getAppConfig exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent updateAppConfig(Map<String, Object> event, Context context) {
        if (Utils.isBlank(SAAS_BOOST_EVENT_BUS)) {
            throw new IllegalStateException("Missing environment variable SAAS_BOOST_EVENT_BUS");
        }
        if (Utils.isBlank(RESOURCES_BUCKET)) {
            throw new IllegalStateException("Missing environment variable RESOURCES_BUCKET");
        }
        if (Utils.isBlank(API_APP_CLIENT)) {
            throw new IllegalStateException("Missing required environment variable API_APP_CLIENT");
        }
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsService::updateAppConfig");
        Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;

        AppConfig updatedAppConfig = Utils.fromJson((String) event.get("body"), AppConfig.class);
        if (updatedAppConfig == null) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(400)
                    .withBody("{\"message\":\"Invalid request body.\"}");
        } else if (updatedAppConfig.getName() == null || updatedAppConfig.getName().isEmpty()) {
            LOGGER.error("Can't update application configuration without an app name");
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(400)
                    .withBody("{\"message\":\"Application name is required.\"}");
        } else {
            AppConfig currentAppConfig = dal.getAppConfig();
            if (currentAppConfig.isEmpty()) {
                LOGGER.info("Processing first time app config save");
                // First time setting the app config object don't bother going through all of the validation
                updatedAppConfig = dal.setAppConfig(updatedAppConfig);

                // If the app config has any databases, get the presigned S3 urls to upload bootstrap files
                generateDatabaseBootstrapFileUrl(updatedAppConfig);

                Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                        AppConfigEvent.APP_CONFIG_CHANGED.detailType(),
                        Collections.EMPTY_MAP
                );

                if (AppConfigHelper.isBillingFirstTime(currentAppConfig, updatedAppConfig)) {
                    // 1. We didn't have a billing provider and now we do, trigger setup
                    // Existing provisioned tenants won't be subscribed to a billing plan
                    // so we don't need to update the tenant stacks.
                    LOGGER.info("AppConfig now has a billing provider. Triggering billing setup.");
                    Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                            "Billing System Setup",
                            Map.of("message", "System Setup")
                    );
                }

                response = new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withHeaders(CORS)
                        .withBody(Utils.toJson(updatedAppConfig));
            } else {
                LOGGER.info("Processing update to existing app config");
                List<Map<String, Object>> provisionedTenants = getProvisionedTenants(context);
                boolean provisioned = !provisionedTenants.isEmpty();
                boolean okToUpdate = validateAppConfigUpdate(currentAppConfig, updatedAppConfig, provisioned);
                boolean fireUpdateAppConfigEvent = false;

                if (okToUpdate) {
                    LOGGER.info("Ok to proceed with app config update");
                    if (AppConfigHelper.isDomainChanged(currentAppConfig, updatedAppConfig)) {
                        LOGGER.info("AppConfig domain name has changed");
                        fireUpdateAppConfigEvent = true;
                    }

                    if (AppConfigHelper.isBillingChanged(currentAppConfig, updatedAppConfig)) {
                        String apiKey1 = currentAppConfig.getBilling() != null
                                ? currentAppConfig.getBilling().getApiKey() : null;
                        String apiKey2 = updatedAppConfig.getBilling() != null
                                ? updatedAppConfig.getBilling().getApiKey() : null;
                        LOGGER.info("AppConfig billing provider has changed {} != {}", apiKey1, apiKey2);
                        if (AppConfigHelper.isBillingFirstTime(currentAppConfig, updatedAppConfig)) {
                            // 1. We didn't have a billing provider and now we do, trigger setup
                            // Existing provisioned tenants won't be subscribed to a billing plan
                            // so we don't need to update the tenant stacks.
                            LOGGER.info("AppConfig now has a billing provider. Triggering billing setup.");
                            Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                                    "Billing System Setup",
                                    Map.of("message", "System Setup")
                            );
                        } else if (AppConfigHelper.isBillingRemoved(currentAppConfig, updatedAppConfig)) {
                            // 2. We had a billing provider and now we don't, disable integration
                            LOGGER.info("AppConfig has removed the billing provider.");
                            // TODO how do we cleanup the billing provider integration?
                        } else {
                            // 3. We had a billing provider and we're just changing the value of the key, that is
                            // taken care of by dal.setAppConfig and we don't need to trigger a setup because
                            // it's already been done.
                            LOGGER.info("AppConfig billing provider API key in-place change.");
                        }
                    }

                    if (AppConfigHelper.isServicesChanged(currentAppConfig, updatedAppConfig)) {
                        LOGGER.info("AppConfig application services changed");
                        // Currently you can only remove services if there are no provisioned tenants
                        Set<String> removedServices = AppConfigHelper.removedServices(currentAppConfig, updatedAppConfig);
                        if (!removedServices.isEmpty()) {
                            LOGGER.info("Services {} were removed from AppConfig: deleting their parameters.",
                                    removedServices);
                            for (String serviceName : removedServices) {
                                dal.deleteServiceConfig(currentAppConfig, serviceName);
                            }
                        }
                        fireUpdateAppConfigEvent = true;
                    }

                    // TODO how do we want to deal with tier settings changes?

                    LOGGER.info("Persisting updated app config");
                    updatedAppConfig = dal.setAppConfig(updatedAppConfig);

                    // If the app config has any databases, get the presigned S3 urls to upload bootstrap files
                    if (!provisioned) {
                        generateDatabaseBootstrapFileUrl(updatedAppConfig);
                    }

                    if (fireUpdateAppConfigEvent) {
                        // The provisioning system can take care of modifying/adding/removing infra
                        // due to changes in the app config
                        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                                AppConfigEvent.APP_CONFIG_CHANGED.detailType(),
                                Collections.EMPTY_MAP
                        );
                    }

                    response = new APIGatewayProxyResponseEvent()
                            .withStatusCode(200)
                            .withHeaders(CORS)
                            .withBody(Utils.toJson(updatedAppConfig));
                } else {
                    LOGGER.info("App config update validation failed");
                    response = new APIGatewayProxyResponseEvent()
                            .withStatusCode(400)
                            .withHeaders(CORS)
                            .withBody("{\"message\":\"Application config update validation failed.\"}");
                }
            }
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsService::updateAppConfig exec " + totalTimeMillis);
        return response;
    }

    public APIGatewayProxyResponseEvent deleteAppConfig(Map<String, Object> event, Context context) {
        if (Utils.warmup(event)) {
            //LOGGER.info("Warming up");
            return new APIGatewayProxyResponseEvent().withHeaders(CORS).withStatusCode(200);
        }

        final long startTimeMillis = System.currentTimeMillis();
        LOGGER.info("SettingsService::deleteAppConfig");
        Utils.logRequestEvent(event);
        APIGatewayProxyResponseEvent response;

        try {
            dal.deleteAppConfig();
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(200);
        } catch (Exception e) {
            response = new APIGatewayProxyResponseEvent()
                    .withHeaders(CORS)
                    .withStatusCode(400)
                    .withBody("{\"message\":\"Error deleting application settings.\"}");
        }

        long totalTimeMillis = System.currentTimeMillis() - startTimeMillis;
        LOGGER.info("SettingsService::deleteAppConfig exec " + totalTimeMillis);
        return response;
    }

    public void handleAppConfigEvent(Map<String, Object> event, Context context) {
        if ("saas-boost".equals(event.get("source"))) {
            AppConfigEvent appConfigEvent = AppConfigEvent.fromDetailType((String) event.get("detail-type"));
            if (appConfigEvent != null) {
                switch (appConfigEvent) {
                    case APP_CONFIG_RESOURCE_CHANGED:
                        LOGGER.info("Handling App Config Resource Changed");
                        handleAppConfigResourceChanged(event, context);
                        break;
                    case APP_CONFIG_CHANGED:
                        // We produce this event, but currently aren't consuming it
                        break;
                    case APP_CONFIG_UPDATE_COMPLETED:
                        //  We produce this event, but currently aren't consuming it
                        break;
                    default: {
                        LOGGER.error("Can't find app config event for detail-type {}", event.get("detail-type"));
                        // TODO Throw here? Would end up in DLQ.
                    }
                }
            } else {
                LOGGER.error("Can't find app config event for detail-type {}", event.get("detail-type"));
                // TODO Throw here? Would end up in DLQ.
            }
        } else if ("aws.s3".equals(event.get("source"))) {
            LOGGER.info("Handling App Config Resources File S3 Event");
            handleAppConfigResourcesFileEvent(event, context);
        } else {
            LOGGER.error("Unknown event source " + event.get("source"));
            // TODO Throw here? Would end up in DLQ.
        }
    }

    protected void handleAppConfigResourceChanged(Map<String, Object> event, Context context) {
        Utils.logRequestEvent(event);
        Map<String, Object> detail = (Map<String, Object>) event.get("detail");
        String json = Utils.toJson(detail);
        if (json != null) {
            AppConfig changedAppConfig = Utils.fromJson(json, AppConfig.class);
            if (changedAppConfig != null) {
                boolean update = false;
                AppConfig existingAppConfig = dal.getAppConfig();
                // Only update the services if they were passed in
                if (json.contains("services") && changedAppConfig.getServices() != null) {
                    for (Map.Entry<String, ServiceConfig> changedService : changedAppConfig.getServices().entrySet()) {
                        String changedServiceName = changedService.getKey();
                        ServiceConfig changedServiceConfig = changedService.getValue();
                        ServiceConfig requestedService = existingAppConfig.getServices().get(changedServiceName);
                        ServiceConfig.Builder newServiceConfigBuilder = ServiceConfig.builder(requestedService);
                        if (requestedService != null && changedServiceConfig != null) {
                            // change container repo if passed
                            if (requestedService.getCompute() != null && changedServiceConfig.getCompute() != null) {
                                String changedContainerRepo = changedServiceConfig.getCompute().getContainerRepo();
                                String existingContainerRepo = requestedService.getCompute().getContainerRepo();
                                if (!Utils.nullableEquals(existingContainerRepo, changedContainerRepo)) {
                                    LOGGER.info("Updating service {} ECR repo from {} to {}", changedServiceName,
                                            requestedService.getCompute().getContainerRepo(),
                                            changedServiceConfig.getCompute().getContainerRepo());
                                    // TODO what if the service shouldn't have a container repo, because compute
                                    // TODO is of the wrong type? core stack listener shouldn't fire the ECR repo event
                                    AbstractCompute.Builder existingComputeBuilder = requestedService
                                            .getCompute().builder();
                                    newServiceConfigBuilder = newServiceConfigBuilder
                                            .compute(existingComputeBuilder
                                                    .containerRepo(changedServiceConfig.getCompute().getContainerRepo())
                                                    .build());
                                }
                            }
                            // change s3 bucket name if passed (and if s3 already exists in service config)
                            if (requestedService.getS3() != null && changedServiceConfig.getS3() != null) {
                                String existingBucketName = requestedService.getS3().getBucketName();
                                String newBucketName = changedServiceConfig.getS3().getBucketName();
                                if (!Utils.nullableEquals(existingBucketName, newBucketName)) {
                                    newServiceConfigBuilder = newServiceConfigBuilder.s3(changedServiceConfig.getS3());
                                }
                            }
                            ServiceConfig newServiceConfig = newServiceConfigBuilder.build();
                            if (!newServiceConfig.equals(requestedService)) {
                                LOGGER.info("Updating serviceConfig from {} to {}",
                                        requestedService, newServiceConfig);
                                update = true;
                                dal.setServiceConfig(newServiceConfig);
                            }
                        } else {
                            LOGGER.error("Can't find app config service {}", changedServiceName);
                        }
                    }
                }
                // If there are provisioned tenants, and we just ran an update to the infrastructure
                // we need to update the tenant environments to reflect any changes
                if (update) {
                    List<Map<String, Object>> provisionedTenants = getProvisionedTenants(context);
                    if (!provisionedTenants.isEmpty()) {
                        LOGGER.info("Updated app config with provisioned tenants");
                        Utils.publishEvent(eventBridge, SAAS_BOOST_EVENT_BUS, "saas-boost",
                                AppConfigEvent.APP_CONFIG_UPDATE_COMPLETED.detailType(),
                                Collections.EMPTY_MAP);
                    }
                } else {
                    LOGGER.info("No app config changes to process");
                }
            } else {
                LOGGER.error("Can't parse event detail as AppConfig {}", json);
            }
        } else {
            LOGGER.error("Can't serialize detail to JSON {}", event.get("detail"));
        }
    }

    protected List<Map<String, Object>> getProvisionedTenants(Context context) {
        LOGGER.info("Calling tenant service to fetch all provisioned tenants");
        ApiGatewayHelper api = ApiGatewayHelper.clientCredentialsHelper(API_APP_CLIENT);
        String getTenantsResponseBody = api.authorizedRequest("GET", "tenants?status=provisioned");
        List<Map<String, Object>> tenants = Utils.fromJson(getTenantsResponseBody, ArrayList.class);
        if (tenants == null) {
            tenants = new ArrayList<>();
        }
        return tenants;
    }

    protected void handleAppConfigResourcesFileEvent(Map<String, Object> event, Context context) {
        Utils.logRequestEvent(event);

        // A database bootstrap file was uploaded for one of the app config services.
        // We'll update the service config so Onboarding will know to run the file as
        // part of provisioning RDS.
        Map<String, Object> detail = (Map<String, Object>) event.get("detail");
        String bucket = (String) ((Map<String, Object>) detail.get("bucket")).get("name");
        String key = (String) ((Map<String, Object>) detail.get("object")).get("key");
        LOGGER.info("Processing resources bucket PUT {}, {}", bucket, key);

        // key will be services/${service_name}/bootstrap.sql
        String serviceName = key.substring("services/".length(), (key.length() - "/bootstrap.sql".length()));
        AppConfig appConfig = dal.getAppConfig();
        for (Map.Entry<String, ServiceConfig> serviceConfig : appConfig.getServices().entrySet()) {
            if (serviceName.equals(serviceConfig.getKey())) {
                ServiceConfig service = serviceConfig.getValue();
                LOGGER.info("Saving bootstrap.sql file for {}", service.getName());
                service.getDatabase().setBootstrapFilename(key);
                dal.setServiceConfig(service);
                break;
            }
        }
    }

    protected static boolean validateAppConfigUpdate(AppConfig currentAppConfig, AppConfig updatedAppConfig,
                                                     boolean provisionedTenants) {
        boolean domainNameValid = true;
        if (AppConfigHelper.isDomainChanged(currentAppConfig, updatedAppConfig) && provisionedTenants) {
            LOGGER.error("Can't change domain name after onboarding tenants");
            domainNameValid = false;
        }

        boolean serviceConfigValid = true;
        if (AppConfigHelper.isServicesChanged(currentAppConfig, updatedAppConfig)) {
            if (provisionedTenants) {
                Set<String> removedServices = AppConfigHelper.removedServices(currentAppConfig, updatedAppConfig);
                if (!removedServices.isEmpty()) {
                    LOGGER.error("Can't remove existing application services after onboarding tenants");
                    serviceConfigValid = false;
                }
            }
        }

        return domainNameValid && serviceConfigValid;
    }

    protected void generateDatabaseBootstrapFileUrl(AppConfig appConfig) {
        // Create the pre-signed S3 URLs for the bootstrap SQL files. We won't save these to the
        // database record because the user might not upload any SQL files. If they do, we'll
        // process those uploads async and persist the relevant data to the database.
        for (Map.Entry<String, ServiceConfig> serviceConfig : appConfig.getServices().entrySet()) {
            String serviceName = serviceConfig.getKey();
            ServiceConfig service = serviceConfig.getValue();
            if (service.hasDatabase() && Utils.isBlank(service.getDatabase().getBootstrapFilename())) {
                try {
                    // Create a presigned S3 URL to upload the database bootstrap file to
                    final String key = "services/" + serviceName + "/bootstrap.sql";
                    final Duration expires = Duration.ofMinutes(15); // UI times out in 10 min
                    PresignedPutObjectRequest presignedObject = presigner.presignPutObject(request -> request
                            .signatureDuration(expires)
                            .putObjectRequest(PutObjectRequest.builder()
                                    .bucket(RESOURCES_BUCKET)
                                    .key(key)
                                    .build()
                            ).build()
                    );
                    service.getDatabase().setBootstrapFilename(presignedObject.url().toString());
                } catch (S3Exception s3Error) {
                    LOGGER.error("s3 presign url failed", s3Error);
                    LOGGER.error(Utils.getFullStackTrace(s3Error));
                    throw s3Error;
                }
            }
        }
    }

    interface AppConfigServiceDependencyFactory {

        AppConfigDataAccessLayer dal();

        EventBridgeClient eventBridge();

        S3Presigner s3Presigner();
    }

    private static final class DefaultDependencyFactory implements AppConfigServiceDependencyFactory {

        @Override
        public AppConfigDataAccessLayer dal() {
            return new AppConfigDataAccessLayer(Utils.sdkClient(DynamoDbClient.builder(), DynamoDbClient.SERVICE_NAME),
                    APP_CONFIG_TABLE, Utils.sdkClient(AcmClient.builder(), AcmClient.SERVICE_NAME),
                    Utils.sdkClient(Route53Client.builder(), Route53Client.SERVICE_NAME));
        }

        @Override
        public EventBridgeClient eventBridge() {
            return Utils.sdkClient(EventBridgeClient.builder(), EventBridgeClient.SERVICE_NAME);
        }

        @Override
        public S3Presigner s3Presigner() {
            try {
                return S3Presigner.builder()
                        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                        .region(Region.of(AWS_REGION))
                        .endpointOverride(new URI("https://" + S3Client.SERVICE_NAME + "."
                                + Region.of(AWS_REGION)
                                + "."
                                + Utils.endpointSuffix(AWS_REGION)))
                        .build();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
    }

}