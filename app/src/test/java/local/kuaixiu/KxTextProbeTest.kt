package local.kuaixiu

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import local.kuaixiu.data.KxText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * 探针：大规模迁移前先验证三件事——
 * ① Robolectric 能解析真实 strings.xml；② KxApp 在测试里也跑到了（KxText 可用）；
 * ③ values / values-en 限定符各自生效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class KxTextProbeTest {

    @Test fun `中文限定符下取到中文且KxApp已注入`() {
        assertEquals("首页", KxText.s(R.string.tab_home))
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("快休", app.getString(R.string.app_name))
    }

    @Test fun `英文 locale 走 values-en`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cfg = Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val en = app.createConfigurationContext(cfg)
        assertEquals("Home", en.getString(R.string.tab_home))
        assertEquals("Kuaixiu", en.getString(R.string.app_name))
    }
}
