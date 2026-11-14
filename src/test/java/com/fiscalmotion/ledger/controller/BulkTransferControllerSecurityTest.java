package com.fiscalmotion.ledger.controller;

import com.fiscalmotion.ledger.dto.BulkTransferRequest;
import com.fiscalmotion.ledger.dto.BulkTransferResponse;
import com.fiscalmotion.ledger.service.BulkTransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "fiscalmotion.bulk.enabled=true",
        "fiscalmotion.security.admin.username=testadmin",
        "fiscalmotion.security.admin.password=testAdminPassword123",
        "fiscalmotion.security.user.username=testuser",
        "fiscalmotion.security.user.password=testUserPassword123"
})
@AutoConfigureMockMvc
class BulkTransferControllerSecurityTest {

    private static final String URL =
            "/api/v1/ledger/transfers/bulk";

    private static final String REQUEST = """
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

    @MockitoBean
    private BulkTransferService bulkTransferService;

    @Test
    void anonymousUserCannotSubmitBulkTransfer() throws Exception {

        mockMvc.perform(
                post(URL)
                        .header("Batch-Id", "security-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST)
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(bulkTransferService);
    }

    @Test
    void regularUserCannotSubmitBulkTransfer() throws Exception {

        mockMvc.perform(
                post(URL)
                        .with(httpBasic(
                                "testuser",
                                "testUserPassword123"
                        ))
                        .header("Batch-Id", "security-test-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST)
        ).andExpect(status().isForbidden());

        verifyNoInteractions(bulkTransferService);
    }

    @Test
    void adminCanSubmitBulkTransfer() throws Exception {

        String batchId = "security-test-003";

        BulkTransferResponse response = new BulkTransferResponse(
                batchId,
                1,
                1,
                0,
                0,
                List.of(
                        new BulkTransferResponse.RowResult(
                                2,
                                "SUCCESS",
                                UUID.randomUUID(),
                                null
                        )
                )
        );

        when(bulkTransferService.process(
                eq(batchId),
                any(BulkTransferRequest.class),
                eq("testadmin")
        )).thenReturn(response);

        mockMvc.perform(
                        post(URL)
                                .with(httpBasic(
                                        "testadmin",
                                        "testAdminPassword123"
                                ))
                                .header("Batch-Id", batchId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId))
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].status").value("SUCCESS"));

        verify(bulkTransferService).process(
                eq(batchId),
                any(BulkTransferRequest.class),
                eq("testadmin")
        );
    }

    @Test
    void wrongAdminPasswordIsRejected() throws Exception {

        mockMvc.perform(
                post(URL)
                        .with(httpBasic(
                                "testadmin",
                                "wrongPassword"
                        ))
                        .header("Batch-Id", "security-test-004")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST)
        ).andExpect(status().isUnauthorized());

        verifyNoInteractions(bulkTransferService);
    }
}