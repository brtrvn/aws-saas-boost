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

import org.junit.Test;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.*;

import static org.junit.Assert.*;

public class CidrDynamoDBTest {

    @Test
    public void testGenerateBatches() {
        List<String> expected = new ArrayList<>();
        for (int octet = 0; octet < 256; octet++) {
            for (int slash21 = 0; slash21 < 256; slash21 +=8) {
               expected.add(String.format("10.%d.%d.0", octet, slash21));
            }
        }
        assertEquals("256 /21 VPCs = 8192 VPCs", 8192, expected.size());
        List<List<WriteRequest>> batches = CidrDynamoDB.generateBatches();

        int vpc = 0;
        for (int i = 0; i < batches.size(); i++) {
            List<WriteRequest> batch = batches.get(i);
            for (int b = 0; b < batch.size(); b++) {
                String cidr = batch.get(b).putRequest().item().get("cidr_block").s();
                assertEquals("Batch " + i + " batch size " + batch.size()
                        + " expected index " + vpc + " batch item " + b, expected.get(vpc++), cidr);
            }
        }

        // We should have 8192 / 25 (328 batches)
        assertEquals(Math.round(expected.size() / 25.0d), batches.size(), 0.0d);

        // All but the final batch should be filled to the limit
        for (int i = 0; i < (batches.size() - 1); i++) {
            assertEquals(25, batches.get(i).size());
        }
        // and one remainder batch
        assertEquals(expected.size() % 25.0d, batches.get((batches.size() - 1)).size(), 0.0d);
    }
}