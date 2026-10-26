package app.runout

import app.runout.soulseek.PortMapper
import app.runout.soulseek.PortState
import app.runout.soulseek.SoulseekShelf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PortMapperTest {
    @Test fun `a nat-pmp request asks for the same port outside, and a reply says which it got`() {
        val req = PortMapper.natPmpMapRequest(45123, 3600)
        assertArrayEquals(byteArrayOf(0, 2, 0, 0, 0xB0.toByte(), 0x43, 0xB0.toByte(), 0x43, 0, 0, 0x0E, 0x10), req)
        // Removing asks for external port 0 and lifetime 0.
        assertArrayEquals(byteArrayOf(0, 2, 0, 0, 0xB0.toByte(), 0x43, 0, 0, 0, 0, 0, 0), PortMapper.natPmpMapRequest(45123, 0))

        val ok = byteArrayOf(0, 130.toByte(), 0, 0, 0, 0, 1, 0, 0xB0.toByte(), 0x43, 0xB0.toByte(), 0x43, 0, 0, 0x0E, 0x10)
        assertEquals(45123, PortMapper.parseNatPmpMap(ok))
        val refused = ok.copyOf().also { it[3] = 2 } // result 2: not authorised
        assertNull(PortMapper.parseNatPmpMap(refused))
        assertNull(PortMapper.parseNatPmpMap(byteArrayOf(0, 130.toByte())))

        val external = byteArrayOf(0, 128.toByte(), 0, 0, 0, 0, 1, 0, 203.toByte(), 0, 113, 7)
        assertEquals("203.0.113.7", PortMapper.parseNatPmpExternal(external))
    }

    @Test fun `ssdp gives the router's description, and that names its wan service and control url`() {
        val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.1.1:5000/rootDesc.xml\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        assertEquals("http://192.168.1.1:5000/rootDesc.xml", PortMapper.ssdpLocation(answer))
        assertNull(PortMapper.ssdpLocation("HTTP/1.1 200 OK\r\n\r\n"))

        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceList><device><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType><controlURL>/ctl/CmnIfCfg</controlURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL></service>
            </serviceList></device></deviceList></device></root>"""
        val (service, control) = PortMapper.parseIgd(xml, "http://192.168.1.1:5000/rootDesc.xml")!!
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", service)
        assertEquals("http://192.168.1.1:5000/ctl/IPConn", control)
        assertNull(PortMapper.parseIgd("<root><device/></root>", "http://192.168.1.1/x.xml"))
    }

    @Test fun `the mapping asks for tcp, the same port both sides, and names sonic`() {
        val add = PortMapper.addArgs(45123, "192.168.1.20", 3600)
        listOf("<NewExternalPort>45123<", "<NewInternalPort>45123<", "<NewProtocol>TCP<", "<NewInternalClient>192.168.1.20<", "<NewPortMappingDescription>Sonic<", "<NewLeaseDuration>3600<")
            .forEach { assertTrue(it, add.contains(it)) }
        assertEquals("203.0.113.7", PortMapper.tagValue("<s:Envelope><u:R><NewExternalIPAddress>203.0.113.7</NewExternalIPAddress></u:R></s:Envelope>", "NewExternalIPAddress"))
    }

    @Test fun `an open port behind a second router is called cgnat, and a matching address is open`() {
        val open = PortState.Open(45123, "upnp", "203.0.113.7")
        assertEquals(open, PortMapper.verdict(open, "203.0.113.7"))
        assertEquals(open, PortMapper.verdict(open, null))
        // The router's outside address is in the carrier's shared space, or isn't what the internet sees.
        assertEquals(PortState.BehindCgnat(45123, "100.72.3.9", null), PortMapper.verdict(open.copy(external = "100.72.3.9"), null))
        assertEquals(PortState.BehindCgnat(45123, "203.0.113.7", "198.51.100.4"), PortMapper.verdict(open, "198.51.100.4"))
        // Nothing to compare: leave it as the router said.
        assertEquals(open.copy(external = null), PortMapper.verdict(open.copy(external = null), "198.51.100.4"))
        assertEquals(PortState.Closed("x", 45123, "192.168.1.20"), PortMapper.verdict(PortState.Closed("x", 45123, "192.168.1.20"), "198.51.100.4"))

        listOf("10.0.0.1", "172.16.4.1", "172.31.255.1", "192.168.1.1", "100.64.0.1", "100.127.255.254").forEach { assertTrue(it, PortMapper.isPrivate(it)) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "203.0.113.7", "nonsense").forEach { assertFalse(it, PortMapper.isPrivate(it)) }
    }

    @Test fun `a shelf that didn't open says why`() {
        val open = PortState.Open(45123, "upnp", "203.0.113.7")
        val closed = PortState.Closed("no", 45123, "192.168.1.20")
        fun failed(reason: String?) = SoulseekShelf("1", "maya", loading = false, error = "e", reason = reason)
        assertEquals(null, SoulseekShelf("1", "maya").problem(true, open))
        assertTrue(failed("OFFLINE").problem(true, open)!!.startsWith("maya is offline"))
        assertTrue(failed("UNREACHABLE").problem(true, open)!!.contains("open a port on their side"))
        assertTrue(failed("UNREACHABLE").problem(true, closed)!!.contains("neither of you has an open port"))
        assertTrue(failed("NO_ANSWER").problem(true, open)!!.startsWith("maya didn't answer"))
        assertEquals("sign in to see their shelf.", failed("OFFLINE").problem(false, open))

        fun empty(total: Int) = SoulseekShelf("1", "maya", loading = false, total = total)
        assertEquals("maya isn't sharing anything right now.", empty(0).problem(true, open))
        assertEquals("maya shares 42 files, but no flac albums.", empty(42).problem(true, open))
        assertEquals("maya shares no flac albums right now.", empty(-1).problem(true, open))
    }
}
