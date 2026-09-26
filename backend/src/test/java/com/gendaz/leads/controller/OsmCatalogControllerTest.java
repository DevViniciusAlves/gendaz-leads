package com.gendaz.leads.controller;

import com.gendaz.leads.dto.osm.OsmRegionSyncRequest;
import com.gendaz.leads.dto.osm.OsmSyncResponse;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import com.gendaz.leads.service.OsmCatalogSyncService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OsmCatalogControllerTest {

    @Mock
    OsmCatalogSyncService syncService;
    @Mock
    com.gendaz.leads.service.OsmTargetService targetService;
    @Mock
    SecurityService securityService;
    @Mock
    UserRepository userRepository;

    @InjectMocks
    OsmCatalogController controller;

    @Test
    void regionSyncReturns202WithSyncRunIdAndRegionId() {
        when(securityService.currentEmail()).thenReturn("user@test.com");
        User user = User.builder().id(7L).email("user@test.com")
                .fullName("Teste").passwordHash("h").role("USER").enabled(true).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(2L);
        region.setCity("Cuiabá");
        region.setState("Mato Grosso");
        region.setCountry("Brasil");

        OsmSyncRun run = new OsmSyncRun();
        run.setId(55L);
        run.setRegion(region);
        run.setStatus("QUEUED");
        when(syncService.requestExistingRegionSync(eq(2L), eq("nail designer"), eq(user)))
                .thenReturn(run);

        ResponseEntity<OsmSyncResponse> res =
                controller.requestRegionSync(2L, new OsmRegionSyncRequest("nail designer"));

        assertEquals(HttpStatus.ACCEPTED, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(55L, res.getBody().syncRunId());
        assertEquals(2L, res.getBody().regionId());
        verify(syncService).dispatchSync(run);
    }

    @Test
    void regionSyncEndpointRequiresAuthentication() throws Exception {
        var method = OsmCatalogController.class.getMethod(
                "requestRegionSync", Long.class, OsmRegionSyncRequest.class);
        assertNotNull(method.getAnnotation(PreAuthorize.class),
                "POST /api/osm-catalog/regions/{regionId}/sync deve exigir autenticacao");
    }

    @Test
    void regionSyncNicheIsRequired() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<OsmRegionSyncRequest>> blankViolations =
                validator.validate(new OsmRegionSyncRequest("  "));
        assertFalse(blankViolations.isEmpty(), "niche em branco deve violar @NotBlank");

        Set<ConstraintViolation<OsmRegionSyncRequest>> okViolations =
                validator.validate(new OsmRegionSyncRequest("nail designer"));
        assertTrue(okViolations.isEmpty());
    }
}
