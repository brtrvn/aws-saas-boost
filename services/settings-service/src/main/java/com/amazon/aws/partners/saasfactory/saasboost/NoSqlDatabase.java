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

import java.util.regex.Pattern;

@JsonDeserialize(builder = NoSqlDatabase.Builder.class)
public class NoSqlDatabase {

    private static final Pattern DYNAMODB_REGEX = Pattern.compile("[a-zA-Z0-9_\\.-]{3,255}");
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

    public static boolean isValidPrimaryKey(String primaryKey) {
        return DYNAMODB_REGEX.matcher(primaryKey).matches();
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
            if (!isValidPrimaryKey(primaryKey)) {
                throw new IllegalArgumentException("Index names must be between 3 and 255 characters long, and can contain only the following characters: a-z, A-Z, 0-9, _ (underscore), - (dash), and . (dot)");
            }
            this.primaryKey = primaryKey;
            return this;
        }

        public NoSqlDatabase build() {
            return new NoSqlDatabase(this);
        }

    }
}
