/**
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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = NoSqlDatabase.Builder.class)
public class NoSqlDatabase {

    private final String noSqlType;
    private final String primaryKey;

    private NoSqlDatabase(Builder builder) {
        this.noSqlType = builder.noSqlType;
        this.primaryKey = builder.primaryKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getNoSqlType() {
        return noSqlType;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    @JsonPOJOBuilder(withPrefix = "") // setters aren't named with[Property]
    public static final class Builder {

        private String noSqlType = "DynamoDB";
        private String primaryKey;

        private Builder builder() {
            return new Builder();
        }

        public Builder noSqlType(String noSqlType) {
            this.noSqlType = noSqlType;
            return this;
        }

        public Builder primaryKey(String primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public NoSqlDatabase build() {
            return new NoSqlDatabase(this);
        }

    }
}
