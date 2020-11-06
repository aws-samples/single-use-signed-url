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

'use strict';
const AWS = require('aws-sdk');
const fs = require('fs');
const uuid = fs.readFileSync('uuid.txt');
const ssm = new AWS.SSM();
const dynamoDB = new AWS.DynamoDB.DocumentClient();
const secretsManager = new AWS.SecretsManager();
const cfDomainParamName = "singleusesignedurl-domain-" + uuid,
    activeKeysTableParamName = "singleusesignedurl-activekeys-" + uuid,
    keyPairIdParamName = "singleusesignedurl-keyPairId-" + uuid,
    secretNameParamName = "singleusesignedurl-secretName-" + uuid,
    apiendpointParamName = "singleusesignedurl-api-endpoint-" + uuid;
const paramQuery = {
    "Names": [cfDomainParamName, activeKeysTableParamName, keyPairIdParamName, secretNameParamName, apiendpointParamName],
    "WithDecryption": true
}
let dynamoDBTableName = '',
    domain = '',
    cloudFrontURL = '',
    secretName = '',
    keyPairId = '',
    apiendpoint = '';

/* Use the Secrets Manager to get the PEM file from secret variable 'secretName' which was previously
   created by CloudFront
   see:
   https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-trusted-signers.html#private-content-creating-cloudfront-key-pairs
 */
const getSecurePEM = (secretName) => {
    return new Promise((resolve, reject) => {
        secretsManager.getSecretValue({SecretId: secretName}, function (err, data) {
            if (err) {
                return reject(err);
            } else {
                var secretData;
                if ('SecretString' in data) {
                    secretData = data.SecretString;
                } else {
                    let buff = new Buffer(data.SecretBinary, 'base64');
                    secretData = buff.toString('ascii');
                }
                //console.log("PEM: " + secretData);
                resolve(secretData);
            }
        })
    });
}

const getSystemsManagerValues = (query) => {
    return new Promise((resolve, reject) => {
        ssm.getParameters(query, function (err, data) {
            if (err) {
                return reject(err);
            }
            for (const i of data.Parameters) {
                if (i.Name === activeKeysTableParamName) {
                    dynamoDBTableName = i.Value
                } else if (i.Name === cfDomainParamName) {
                    domain = i.Value
                } else if (i.Name === secretNameParamName) {
                    secretName = i.Value
                } else if (i.Name === keyPairIdParamName) {
                    keyPairId = i.Value
                } else if (i.Name === apiendpointParamName) {
                    apiendpoint = i.Value
                }
            }
            resolve({});
        })
    });
}

/* Create a signed URL
 */
const getSignedURL = (signer, options) => {
    return new Promise((resolve, reject) => {
        signer.getSignedUrl(options, function (err, data) {
            if (err) {
                reject(err);
            } else {
                resolve(data);
            }
        });
    })
}

/* Write the UUID, SignedURL, and valid until date to the database,
   Pass along the url as the result if successful
 */
const writeRecordToDynamoDB = (url, uuid, file, validuntil) => {
    return new Promise((resolve, reject) => {
        let params = {
            TableName: dynamoDBTableName,
            Item: {
                id: uuid,
                file: file,
                validuntil: validuntil
            }
        };
        dynamoDB
            .put(params)
            .promise()
            .then(res => {
                console.info("Sent data to DynamoDB");
                resolve(url); // pass the data along
            }).catch(err => {
            reject(err);
        });
    })
}
exports.handler = function (event, context) {
    console.info("event: " + JSON.stringify(event));
    console.info("context: " + JSON.stringify(context));

    let timeout = 0;
    let file = '';
    let epoch = 0;

    getSystemsManagerValues(paramQuery).then(smParams => {
        cloudFrontURL = "https://" + domain + "/";
        if (!event.queryStringParameters) {
            let html = fs.readFileSync('generate.html', 'utf8');
            html = html.replace('{API_ENDPOINT}', apiendpoint)
            context.succeed({
                statusCode: 200,
                headers: {
                    'Content-Type': 'text/html'
                },
                body: html
            });
        }
        timeout = parseInt(event.queryStringParameters.timeout);
        file = event.queryStringParameters.file;
        epoch = parseInt(((Date.now() + 0) / 1000) + timeout);
        console.info("Timeout: " + timeout + " Expires: " + epoch + " File: " + file);

        return getSecurePEM(secretName);
    }).then(pem => {
        return getSignedURL(new AWS.CloudFront.Signer(keyPairId, pem), {
            "url": cloudFrontURL + file + "?id=" + context.awsRequestId,
            expires: epoch
        });
    }).then(signedURL => {
        console.info("URL: " + signedURL);
        return writeRecordToDynamoDB(signedURL, context.awsRequestId, file, epoch);
    }).then(data => {
        // singed url created and store in dynamodb at this point so return a success code with detailed body.
        context.succeed({
            statusCode: 200,
            headers: {
                "Access-Control-Allow-Headers": "Content-Type",
                "Access-Control-Allow-Origin": "*",
                "Access-Control-Allow-Methods": "OPTIONS,GET"
            },
            body: JSON.stringify({
                id: context.awsRequestId,
                url: data,
                validuntil: '' + epoch
            })
        });
    }).catch(err => {
        context.fail(err);
    });
};
