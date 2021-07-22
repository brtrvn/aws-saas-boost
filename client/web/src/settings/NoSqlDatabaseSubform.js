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

import React, { Fragment } from "react";
import { Row, Col, Card, CardBody, CardHeader } from "reactstrap";
import {
  SaasBoostSelect,
  SaasBoostInput,
  SaasBoostCheckbox
} from "../components/FormComponents";

export default class NoSqlDatabaseSubform extends React.Component {
    getEngineOptions() {
        const options = this.props.noSqlDbOptions?.map((engine) => {
          return (
            <option value="DynamoDB" key="DynamoDB">
              Amazon DynamoDB
            </option>
          );
        });
        return options;
      }

    render() {
        return (
          <Fragment>
            <Row>
              <Col xs={12}>
                <Card>
                  <CardHeader>NoSQL Database</CardHeader>
                  <CardBody>
                    <SaasBoostCheckbox
                      name="provisionNoSqlDb"
                      id="provisionNoSqlDb"
                      label="Provision a noSQL database for the application"
                      value={this.props.provisionNoSqlDb}
                    />
                    {this.props.provisionNoSqlDb && (
                      <Row>
                        <Col xs={12}>
                          <SaasBoostSelect
                            label="Engine"
                            name="noSqlDatabase.engine"
                            id="noSqlDatabase.engine"
                            value={this.props.values?.engine}
                            disabled={this.props.isLocked}
                          >
                            <option value="">Please select</option>
                            {this.getEngineOptions()}
                          </SaasBoostSelect>
                          <SaasBoostInput
                            key="noSqlDatabase.primaryKey"
                            label="Primary Key"
                            name="noSqlDatabase.primaryKey"
                            type="text"
                            disabled={this.props.isLocked}
                          />
                        </Col>
                      </Row>
                    )}
                  </CardBody>
                </Card>
              </Col>
            </Row>
          </Fragment>
        );
      }
    }