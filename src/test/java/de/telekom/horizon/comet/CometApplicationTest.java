// Copyright 2024 Deutsche Telekom IT GmbH
//
// SPDX-License-Identifier: Apache-2.0

package de.telekom.horizon.comet;

import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import de.telekom.horizon.comet.test.utils.HazelcastTestInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ExtendWith(HazelcastTestInstance.class)
class CometApplicationTest extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

    @Override
    public String getEventType() {
        return "foo";
    }
}