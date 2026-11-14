package com.fiscalmotion.ledger.config;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.httpBasic;

import static org.springframework.test.web.servlet.request
        .MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result
        .MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "fiscalmotion.bulk.enabled=false",
        "fiscalmotion.security.admin.username=testadmin",
        "fiscalmotion.security.admin.password=testAdminPassword123",
        "fiscalmotion.security.user.username=testuser",
        "fiscalmotion.security.user.password=testUserPassword123"
})
@AutoConfigureMockMvc
class BulkTransferSecurityIntegrationTest {

    private static final String BULK_URL =
            "/api/v1/ledger/transfers/bulk";

    private static final String BODY = """
            {
              "transfers": [
                {
                  "sourceAccount": "ACC001",
                  "targetAccount": "ACC002",
                  "amount": 100.00
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousUserShouldReceive401() throws Exception {

        mockMvc.perform(
                post(BULK_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Batch-Id", "test-batch-001")
                        .content(BODY)
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void regularUserShouldReceive403() throws Exception {

        mockMvc.perform(
                post(BULK_URL)
                        .with(httpBasic(
                                "testuser",
                                "testUserPassword123"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Batch-Id", "test-batch-002")
                        .content(BODY)
        ).andExpect(status().isForbidden());
    }

    @Test
    void invalidPasswordShouldReceive401() throws Exception {

        mockMvc.perform(
                post(BULK_URL)
                        .with(httpBasic(
                                "testadmin",
                                "wrongPassword"
                        ))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Batch-Id", "test-batch-003")
                        .content(BODY)
        ).andExpect(status().isUnauthorized());
    }
}