package com.kardo.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientIpFilterTest {

    private final ClientIpFilter filter = new ClientIpFilter("CF-Connecting-IP");

    private String remoteAddrSeenBy(MockHttpServletRequest request) throws Exception {
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req));
        return seen.get().getRemoteAddr();
    }

    @Test
    @DisplayName("Usa la IP que pone Cloudflare e ignora la X-Forwarded-For del cliente")
    void prefersTrustedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("1.2.3.4"); // lo que dejaria ForwardedHeaderFilter: la IP inventada
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");

        assertThat(remoteAddrSeenBy(request)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("Acepta IPv6")
    void acceptsIpv6() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "2001:db8::1");

        assertThat(remoteAddrSeenBy(request)).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("Sin cabecera de confianza deja la peticion como estaba")
    void leavesRequestAloneWithoutHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");

        assertThat(remoteAddrSeenBy(request)).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("Descarta un valor que no parece una IP")
    void ignoresGarbage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("CF-Connecting-IP", "<script>alert(1)</script>");

        assertThat(remoteAddrSeenBy(request)).isEqualTo("10.0.0.5");
    }
}
