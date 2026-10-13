package local.kuaixiu

import local.kuaixiu.data.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新检测的纯逻辑：版本比较 + 官网清单解析（网络部分不在此测）。 */
class UpdateCheckerTest {

    @Test fun `版本比较_更高更低相等`() {
        assertTrue(UpdateChecker.compareVersions("0.8.0", "0.7.0") > 0)
        assertTrue(UpdateChecker.compareVersions("0.7.0", "0.8.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.7.0", "0.7.0"))
    }

    @Test fun `版本比较_数值逐段而不是字符串`() {
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "0.99.99") > 0)
    }

    @Test fun `版本比较_兼容v前缀与短段`() {
        assertEquals(0, UpdateChecker.compareVersions("v0.7.0", "0.7.0"))
        assertEquals(0, UpdateChecker.compareVersions("V0.7", "0.7.0"))
        assertTrue(UpdateChecker.compareVersions("0.7.1", "0.7") > 0)
    }

    @Test fun `清单_notes数组与三段下载入口`() {
        val info = UpdateChecker.parseManifest(
            """
            {
              "version": "0.8.0",
              "releaseDate": "2026-10-20",
              "notes": ["新评分口径", "修复连续天数清零"],
              "downloads": {
                "quark": "https://pan.quark.cn/s/abc",
                "baidu": "https://pan.baidu.com/s/def?pwd=1",
                "site": "https://ligdesign.win/kuaixiu"
              }
            }
            """.trimIndent()
        )!!
        assertEquals("0.8.0", info.version)
        assertEquals("2026-10-20", info.releaseDate)
        assertEquals(listOf("新评分口径", "修复连续天数清零"), info.notes)
        assertEquals(listOf("夸克网盘", "百度网盘", "官网"), info.downloads.map { it.first })
    }

    @Test fun `清单_notes可以是整段文本且缺的下载入口不出现`() {
        val info = UpdateChecker.parseManifest(
            """{"version": "v0.8.0", "notes": "第一行\n第二行", "downloads": {"quark": "https://pan.quark.cn/s/abc"}}"""
        )!!
        assertEquals("0.8.0", info.version)
        assertEquals(listOf("第一行", "第二行"), info.notes)
        assertEquals(listOf("夸克网盘"), info.downloads.map { it.first })
        assertNull(info.releaseDate)
    }

    @Test fun `清单_软404的首页HTML不算清单`() {
        assertNull(UpdateChecker.parseManifest("<!DOCTYPE html><html><head><title>404</title></head></html>"))
    }

    @Test fun `清单_版本号非法或缺字段返回null`() {
        assertNull(UpdateChecker.parseManifest("""{"version": "latest"}"""))
        assertNull(UpdateChecker.parseManifest("""{"notes": ["没有版本号"]}"""))
        assertNull(UpdateChecker.parseManifest("not json at all"))
    }
}
