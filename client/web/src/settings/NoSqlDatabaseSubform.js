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

import React from "react";
import { Row, Col, Card, CardHeader, CardBody } from "reactstrap";
import {
  SaasBoostInput,
  SaasBoostCheckbox,
} from "../components/FormComponents";

export default function NoSqlDatabaseSubform(props) {
  const { provisionNoSql = false } = props;
  return (
    <>
      <Row>
        <Col xs={12}>
          <Card>
            <CardHeader>No SQL Database</CardHeader>
            <CardBody>
              <SaasBoostCheckbox
                name="provisionNoSql"
                id="provisionNoSql"
                label="Configure No SQL Database"
                value={provisionNoSql}
              />
              {provisionNoSql && (
                <Row>
                  <Col xl={6}>
                    <SaasBoostInput
                      key="noSqlDatabase.primaryKey"
                      label="Please enter the primary key"
                      name="noSqlDatabase.primaryKey"
                      type="text"
                    />
                  </Col>
                </Row>
              )}
            </CardBody>
          </Card>
        </Col>
      </Row>
    </>
  );
}
