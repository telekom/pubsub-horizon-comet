package de.telekom.horizon.comet;

import de.telekom.horizon.comet.test.utils.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CometApplicationTest extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

    @Override
    public String getEventType() {
        return "foo";
    }
}