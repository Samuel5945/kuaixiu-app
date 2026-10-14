package local.kuaixiu

import local.kuaixiu.data.ProviderProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切服务商前「这把 Key 该归给谁」的判定。判错的代价是数据丢失：`saveConfig` 无条件写
 * `llmKeyEnc = encrypt(新值)`，没归位的老 Key 会被新档案的空 Key 冲掉，找不回来。
 */
class ProviderHomeTest {

    private val urls = mapOf(
        "智谱" to "https://open.bigmodel.cn/api/paas/v4",
        "DeepSeek" to "https://api.deepseek.com/v1",
    )

    @Test
    fun `已经选过服务商就按档案名`() {
        // 他可能把智谱的地址改过（反代），这时候不能拿 baseUrl 去猜
        assertEquals("DeepSeek", ProviderProfiles.homeOf("DeepSeek", urls.getValue("智谱"), urls))
    }

    @Test
    fun `老版本只有一个 Key 时按地址认`() {
        assertEquals("智谱", ProviderProfiles.homeOf("", "https://open.bigmodel.cn/api/paas/v4", urls))
        assertEquals("智谱", ProviderProfiles.homeOf("", "https://open.bigmodel.cn/api/paas/v4/", urls))
    }

    @Test
    fun `认不出来的返回空，由调用侧保留原 Key`() {
        assertEquals("", ProviderProfiles.homeOf("", "https://my.proxy.test/v1", urls))
        assertEquals("", ProviderProfiles.homeOf("", "", urls))
    }
}
