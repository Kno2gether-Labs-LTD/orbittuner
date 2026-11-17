package dev.avijit.orbittuner.net

import org.junit.Assert.*
import org.junit.Test

class ServiceHostTest {
    @Test fun acceptsBareHostsWithOptionalPort() {
        for (h in listOf("tuner.orbitapps.space", "10.0.2.2:8787", "localhost:8787", "a-b.example")) assertTrue(h, ServiceHost.isValid(h))
    }

    @Test fun rejectsMaliciousHosts() {
        for (h in listOf("https://evil.com", "evil.com/path", "u:p@evil.com", "evil.com?x=1", "evil.com#f", "a b", "evil.com:99999", "evil.com:", "-a.com", "a..com", "evil.com%2Fx", "")) {
            assertFalse("host $h", ServiceHost.isValid(h))
        }
    }

    @Test fun baseUrlSchemeRules() {
        assertEquals("https://tuner.orbitapps.space", ServiceHost.baseUrl("tuner.orbitapps.space", debug = true))
        assertEquals("http://10.0.2.2:8787", ServiceHost.baseUrl("10.0.2.2:8787", debug = true))
        assertEquals("http://localhost:8787", ServiceHost.baseUrl("localhost:8787", debug = true))
        assertEquals("https://10.0.2.2:8787", ServiceHost.baseUrl("10.0.2.2:8787", debug = false))
        assertEquals("https://localhost", ServiceHost.baseUrl("localhost", debug = true))
        assertNull(ServiceHost.baseUrl("evil.com/x", debug = true))
    }

    @Test fun debugBuildsUsePlainHttpForPrivateLanHosts() {
        assertEquals("http://192.168.1.50:8787", ServiceHost.baseUrl("192.168.1.50:8787", debug = true))
        assertEquals("http://10.1.2.3:8787", ServiceHost.baseUrl("10.1.2.3:8787", debug = true))
        assertEquals("http://172.16.0.1:8787", ServiceHost.baseUrl("172.16.0.1:8787", debug = true))
        assertEquals("http://172.31.255.1", ServiceHost.baseUrl("172.31.255.1", debug = true))
        assertEquals("https://172.32.0.1:8787", ServiceHost.baseUrl("172.32.0.1:8787", debug = true))
        assertEquals("https://192.169.1.1:8787", ServiceHost.baseUrl("192.169.1.1:8787", debug = true))
        assertEquals("https://8.8.8.8:8787", ServiceHost.baseUrl("8.8.8.8:8787", debug = true))
        assertEquals("https://192.168.1.50:8787", ServiceHost.baseUrl("192.168.1.50:8787", debug = false))
    }
}
