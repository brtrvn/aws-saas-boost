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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class Auth0DataAccessLayer implements SystemUserDataAccessLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(Auth0DataAccessLayer.class);

    @Override
    public List<SystemUser> getUsers(Map<String, Object> event) {
        return null;
    }

    @Override
    public SystemUser getUser(Map<String, Object> event, String username) {
        return null;
    }

    @Override
    public SystemUser updateUser(Map<String, Object> event, SystemUser user) {
        return null;
    }

    @Override
    public SystemUser enableUser(Map<String, Object> event, String username) {
        return null;
    }

    @Override
    public SystemUser disableUser(Map<String, Object> event, String username) {
        return null;
    }

    @Override
    public SystemUser insertUser(Map<String, Object> event, SystemUser user) {
        return null;
    }

    @Override
    public void deleteUser(Map<String, Object> event, String username) {

    }
}
