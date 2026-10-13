package local.kuaixiu

import local.kuaixiu.data.KxConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** 时区解析：空（跟随系统）与非法值都回退到系统时区，显式值照用。 */
class TimezoneTest {

    @Test fun `空时区跟随系统`() {
        assertEquals(ZoneId.systemDefault(), KxConfig(timezone = "").zone())
    }

    @Test fun `非法时区回退系统`() {
        assertEquals(ZoneId.systemDefault(), KxConfig(timezone = "No/Such_Zone").zone())
    }

    @Test fun `显式时区照用`() {
        assertEquals(ZoneId.of("America/New_York"), KxConfig(timezone = "America/New_York").zone())
    }
}
