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
const dynamoDB = new AWS.DynamoDB({maxRetries: 0});
const cfDomainParamName = "singleusesignedurl-domain-" + uuid,
    activeKeysTableParamName = "singleusesignedurl-activekeys-" + uuid;
const paramQuery = {
    "Names": [cfDomainParamName, activeKeysTableParamName],
    "WithDecryption": true
}
let dynamoDBTableName = '',
    domain = '',
    redirectURL = '';

function redirectReponse(err, callback) {
    const response = {
        status: '302',
        statusDescription: 'Not Found',
        headers: {
            location: [{
                key: 'Location',
                value: redirectURL + '?err=' + err,
            }],
        },
    };
    callback(null, response);
}

function notFoundReponse(callback) {
    const response = {
        status: '404',
        statusDescription: 'Not Found'
    };
    callback(null, response);
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
                }
            }
            resolve({});
        })
    });
}

exports.handler = (event, context, callback) => {
    console.info("Event:" + JSON.stringify(event));
    console.info("Context:" + JSON.stringify(context));
    if (event == undefined || event.Records == undefined || event.Records.length == 0) {
        notFoundReponse(callback);
        return;
    }
    let querystring = event.Records[0].cf.request.querystring;
    let vars = querystring.split('&');
    let id = '';
    for (let i = 0; i < vars.length; i++) {
        let pair = vars[i].split('=');
        if (decodeURIComponent(pair[0]) == 'id') {
            id = decodeURIComponent(pair[1]);
            break;
        }
    }
    console.info("id:" + id);

    if (id == '') {
        notFoundReponse(callback);
        return;
    }

    getSystemsManagerValues(paramQuery).then(smParams => {
        redirectURL = "https://" + domain + "/web/reauth.html";
        let dbQuery = {
            TableName: dynamoDBTableName,
            Key: {
                "id": {
                    "S": id
                },
            }
        };
        dynamoDB
            .getItem(dbQuery)
            .promise()
            .then(res => {
                if (res.Item) { // we found the item so allow access and remove key
                    dynamoDB
                        .deleteItem(dbQuery)
                        .promise()
                        .then(res => {
                            callback(null, event.Records[0].cf.request);
                        })
                        .catch(err => {
                            console.error("DynamoDB Delete Error: " + JSON.stringify(err));
                            redirectReponse(err.code, callback);
                        });
                } else { // item not found so redirect to fallback page
                    redirectReponse('Item not found', callback);
                }
            })
            .catch(err => {
                console.error("Error: " + JSON.stringify(err))
                redirectReponse(err, callback);
            });
    }).catch(err => {
        console.error("Error getting parameter: " + JSON.stringify(err))
        context.fail(err);
    });
};
