package com.walletledger.security;

import com.walletledger.AbstractIntegrationTest;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * When a controller throws something nobody handles, Tomcat forwards to /error. That forward goes
 * through the security filter chain again, and /error is not public, so the client used to get a
 * 401 "not authenticated" instead of the 500 that actually happened. Found by MultiInstanceIT's
 * control experiment: a version conflict between JVMs surfaced as 401.
 */
class ErrorDispatchIT extends AbstractIntegrationTest {

    @Test
    void anErrorDispatchIsNotTurnedIntoAnAuthenticationFailure() throws Exception {
        int status = mockMvc.perform(get("/error").with(request -> {
            request.setDispatcherType(DispatcherType.ERROR);
            return request;
        })).andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(401).isNotEqualTo(403);
    }
}
