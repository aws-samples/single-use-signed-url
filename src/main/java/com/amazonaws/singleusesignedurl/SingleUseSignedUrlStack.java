/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazonaws.singleusesignedurl;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.apigateway.LambdaRestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.ssm.ParameterTier;
import software.amazon.awscdk.services.ssm.StringParameter;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.UUID;

public class SingleUseSignedUrlStack extends Stack {
    public SingleUseSignedUrlStack(final Construct scope, final String id) throws FileNotFoundException {
        this(scope, id, null);
    }

    public SingleUseSignedUrlStack(final Construct scope, final String id, final StackProps props) throws FileNotFoundException {
        super(scope, id, props);
        String uuid = getShortenedUUID();

        Table fileKeyTable = createFileKeyTable(uuid, "singleusesignedurl-activekeys-" + uuid);
        PolicyStatement secretValuePolicy = createGetSecretValuePolicyStatement();
        PolicyStatement getParameterPolicy = createGetParametersPolicyStatement(uuid);
        Function createSignedURLHandler = createCreateSignedURLHandlerFunction(uuid, secretValuePolicy, getParameterPolicy, fileKeyTable);
        LambdaRestApi createSignedURLApi = createCreateSignedURLRestApi(uuid, createSignedURLHandler);
        Version cloudFrontViewRequestHandlerV1 = createcloudFrontViewRequestHandlerFunction(uuid, secretValuePolicy, getParameterPolicy, fileKeyTable);
        Bucket cfLogsBucket = createCloudFrontLogBucket(uuid);
        Bucket filesBucket = createFilesBucket(uuid, "singleusesignedurl-files-" + uuid);
        CloudFrontWebDistribution cloudFrontWebDistribution = createCloudFrontWebDistribution(uuid, cloudFrontViewRequestHandlerV1, cfLogsBucket, filesBucket);
        createParameters(uuid, fileKeyTable, createSignedURLApi, cloudFrontWebDistribution);
    }

    private Table createFileKeyTable(String uuid, String activekeysTableName) {
        Table fileKeyTable = Table.Builder.create(this, "activekeys" + uuid)
                .tableName(activekeysTableName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .partitionKey(Attribute.builder()
                        .name("id")
                        .type(AttributeType.STRING)
                        .build())
                .build();


        return fileKeyTable;
    }

    private PolicyStatement createGetSecretValuePolicyStatement() {
        return PolicyStatement.Builder.create()
                .resources(Arrays.asList("arn:aws:secretsmanager:*:*:secret:*" + this.getNode().tryGetContext("secretName") + "*"))
                .actions(Arrays.asList("secretsmanager:GetSecretValue"))
                .build();
    }

    private PolicyStatement createGetParametersPolicyStatement(String uuid) {
        return PolicyStatement.Builder.create()
                .resources(Arrays.asList("arn:aws:ssm:*:*:parameter/*" + uuid + "*"))
                .actions(Arrays.asList("ssm:GetParameters"))
                .build();
    }

    private Function createCreateSignedURLHandlerFunction(String uuid, PolicyStatement secretValuePolicy, PolicyStatement getParameterPolicy, Table fileKeyTable) {
        Function createSignedURLHandler = Function.Builder.create(this, "CreateSignedURL" + uuid)
                .runtime(Runtime.NODEJS_12_X)
                .functionName("CreateSignedURL" + uuid)
                .handler("CreateSignedURL.handler")
                .code(Code.fromAsset("lambda"))
                .build();

        createSignedURLHandler.addToRolePolicy(secretValuePolicy);
        createSignedURLHandler.addToRolePolicy(getParameterPolicy);
        fileKeyTable.grantReadWriteData(createSignedURLHandler);

        return createSignedURLHandler;
    }

    private LambdaRestApi createCreateSignedURLRestApi(String uuid, Function createSignedURLHandler) {
        LambdaRestApi createSignedURLApi = LambdaRestApi.Builder.create(this, "CreateSignedURLAPI" + uuid)
                .handler(createSignedURLHandler)
                .restApiName("CreateSignedURL" + uuid)
                .deploy(true)
                .deployOptions(StageOptions.builder().stageName("prod").build())
                .build();

        CfnOutput.Builder.create(this, "CreateSignedURL-Output")
                .exportName("CreateSignedURLEndpoint")
                .value(createSignedURLApi.getUrl() + "CreateSignedURL" + uuid)
                .build();
        return createSignedURLApi;
    }

    private Version createcloudFrontViewRequestHandlerFunction(String uuid, PolicyStatement secretValuePolicy, PolicyStatement getParameterPolicy, Table fileKeyTable) {
        Function cloudFrontViewRequestHandler = Function.Builder.create(this, "CloudFrontViewRequest" + uuid)
                .runtime(Runtime.NODEJS_12_X)
                .functionName("CloudFrontViewRequest" + uuid)
                .handler("CloudFrontViewRequest.handler")
                .code(Code.fromAsset("lambda"))
                .build();

        cloudFrontViewRequestHandler.addToRolePolicy(secretValuePolicy);
        cloudFrontViewRequestHandler.addToRolePolicy(getParameterPolicy);

        Version cloudFrontViewRequestHandlerV1 = Version.Builder.create(this, "A" + uuid)
                .lambda(cloudFrontViewRequestHandler)
                .build();

        fileKeyTable.grantReadWriteData(cloudFrontViewRequestHandler);
        return cloudFrontViewRequestHandlerV1;
    }

    private Bucket createCloudFrontLogBucket(String uuid) {
        Bucket cfLogsBucket = Bucket.Builder.create(this, "singleusesignedurl-cf-logs" + uuid)
                .bucketName("singleusesignedurl-cf-logs-" + uuid)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
        CfnOutput.Builder.create(this, "singleusesignedurl-cf-logs-output" + uuid)
                .exportName("singleusesignedurl-cf-logs")
                .value(cfLogsBucket.getBucketName())
                .build();
        return cfLogsBucket;
    }

    private Bucket createFilesBucket(String uuid, String s3FileBucketName) {
        Bucket filesBucket = Bucket.Builder.create(this, "singleusesignedurl-files-bucket" + uuid)
                .bucketName(s3FileBucketName)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
        CfnOutput.Builder.create(this, "singleusesignedurl-files-bucket-output" + uuid)
                .exportName("singleusesignedurl-files-bucket")
                .value(filesBucket.getBucketName())
                .build();
        BucketDeployment.Builder.create(this, "DeployTestFiles" + uuid)
                .destinationKeyPrefix("")
                .sources(Arrays.asList(Source.asset("./files")))
                .destinationBucket(filesBucket)
                .build();
        return filesBucket;
    }

    private CloudFrontWebDistribution createCloudFrontWebDistribution(String uuid, Version cloudFrontViewRequestHandlerV1, Bucket cfLogsBucket, Bucket filesBucket) {
        CloudFrontWebDistribution.Builder cloudFrontWebDistributionBuilder = CloudFrontWebDistribution.Builder.create(this, "SingleUseSignedURL" + uuid);

        Behavior webBehavior = Behavior.builder()
                .pathPattern("web/*")
                .allowedMethods(CloudFrontAllowedMethods.GET_HEAD)
                .build();

        LambdaFunctionAssociation lambdaFunctionAssociation = LambdaFunctionAssociation.builder()
                .lambdaFunction(cloudFrontViewRequestHandlerV1)
                .includeBody(true)
                .eventType(LambdaEdgeEventType.VIEWER_REQUEST)
                .build();

        Behavior distroBehavior = Behavior.builder()
                .allowedMethods(CloudFrontAllowedMethods.GET_HEAD)
                .isDefaultBehavior(true)
                .lambdaFunctionAssociations(Arrays.asList(lambdaFunctionAssociation))
                .build();
        OriginAccessIdentity oadIdentity = OriginAccessIdentity.Builder.create(this, "OAI").build();
        S3OriginConfig s3OriginConfig = S3OriginConfig.builder()
                .s3BucketSource(filesBucket)
                .originAccessIdentity(oadIdentity)
                .build();
        SourceConfiguration scDistro = SourceConfiguration.builder()
                .s3OriginSource(s3OriginConfig)
                .behaviors(Arrays.asList(webBehavior, distroBehavior))
                .build();

        CloudFrontWebDistribution cloudFrontWebDistribution = cloudFrontWebDistributionBuilder
                .comment("Cloud Front distribution to handle single use signed URLs")
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .httpVersion(HttpVersion.HTTP2)
                .loggingConfig(LoggingConfiguration.builder().bucket(cfLogsBucket).prefix("cf-logs/").build())
                .originConfigs(Arrays.asList(scDistro))
                .build();

        CfnOutput.Builder.create(this, "singleusesignedurl-domain")
                .exportName("singleusesignedurl-domain")
                .value(cloudFrontWebDistribution.getDistributionDomainName())
                .build();
        return cloudFrontWebDistribution;
    }

    private void createParameters(String uuid, Table fileKeyTable, LambdaRestApi createSignedURLApi, CloudFrontWebDistribution cloudFrontWebDistribution) {
        //Parameter names
        String cfDomainParamName = "singleusesignedurl-domain-" + uuid;
        String activeKeysTableParamName = "singleusesignedurl-activekeys-" + uuid;
        String keyPairIdParamName = "singleusesignedurl-keyPairId-" + uuid;
        String secretNameParamName = "singleusesignedurl-secretName-" + uuid;
        String apiEndpointParamName = "singleusesignedurl-api-endpoint-" + uuid;
        StringParameter.Builder.create(this, cfDomainParamName)
                .allowedPattern(".*")
                .description("The cloud front domain name")
                .parameterName(cfDomainParamName)
                .stringValue(cloudFrontWebDistribution.getDistributionDomainName())
                .tier(ParameterTier.STANDARD)
                .build();
        StringParameter.Builder.create(this, apiEndpointParamName)
                .allowedPattern(".*")
                .description("The api endpoint used in the demo generate response")
                .parameterName(apiEndpointParamName)
                .stringValue(createSignedURLApi.getUrl() + "CreateSignedURL" + uuid)
                .tier(ParameterTier.STANDARD)
                .build();
        StringParameter.Builder.create(this, keyPairIdParamName)
                .allowedPattern(".*")
                .description("The key pair id to use when signing")
                .parameterName(keyPairIdParamName)
                .stringValue((String) this.getNode().tryGetContext("keyPairId"))
                .tier(ParameterTier.STANDARD)
                .build();
        StringParameter.Builder.create(this, secretNameParamName)
                .allowedPattern(".*")
                .description("The secret id that holds the CloudFront PEM file")
                .parameterName(secretNameParamName)
                .stringValue((String) this.getNode().tryGetContext("secretName"))
                .tier(ParameterTier.STANDARD)
                .build();
        StringParameter.Builder.create(this, activeKeysTableParamName)
                .allowedPattern(".*")
                .description("The database name")
                .parameterName(activeKeysTableParamName)
                .stringValue(fileKeyTable.getTableName())
                .tier(ParameterTier.STANDARD)
                .build();
    }

    public String getShortenedUUID() throws FileNotFoundException {
        String uuid = ((String) this.getNode().tryGetContext("UUID")).replace("-","");
        try (PrintStream out = new PrintStream(new FileOutputStream("./lambda/uuid.txt"))) {
            out.print(uuid);
        }
        return uuid;
    }
}
