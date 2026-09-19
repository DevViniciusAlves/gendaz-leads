package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CreateCampaignRequest;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock
    CampaignRepository campaignRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    AsyncCampaignProcessor processor;
    @Mock
    CampaignRetryTransactionService retryService;
    @Mock
    CampaignCreateTransactionService createTransactionService;
    @Mock
    SecurityService securityService;

    @InjectMocks
    CampaignService campaignService;

    @Test
    void createCallsTransactionServiceThenProcessor() {
        CreateCampaignRequest request = new CreateCampaignRequest("nicho", "local", 5);
        Campaign campaign = Campaign.builder().id(1L).build();
        when(createTransactionService.createCampaign(request)).thenReturn(campaign);

        campaignService.create(request);

        InOrder inOrder = inOrder(createTransactionService, processor);
        inOrder.verify(createTransactionService).createCampaign(request);
        inOrder.verify(processor).processCampaign(1L);
    }
}
