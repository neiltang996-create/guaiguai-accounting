package com.family.ledger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** 同步协议里的纯逻辑：目录约定、错误文案、PROPFIND 解析。 */
class SyncProtocolTest {

    private val dav = "https://dav.jianguoyun.com/dav/"

    @Test
    fun `未带familyledger的地址会自动补上`() {
        assertEquals("https://dav.jianguoyun.com/dav/familyledger", SyncPaths.baseDir(dav))
        assertEquals("https://dav.jianguoyun.com/dav/familyledger", SyncPaths.baseDir("https://dav.jianguoyun.com/dav"))
    }

    @Test
    fun `已经带familyledger的地址不会重复追加`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/familyledger",
            SyncPaths.baseDir("https://dav.jianguoyun.com/dav/familyledger/"),
        )
        // 用户自己建了同名目录（大小写不同）也不重复建
        assertEquals(
            "https://dav.jianguoyun.com/dav/FamilyLedger",
            SyncPaths.baseDir("https://dav.jianguoyun.com/dav/FamilyLedger"),
        )
    }

    @Test
    fun `设备日志路径符合约定`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/familyledger/fam-1/dev-1.jsonl",
            SyncPaths.journalUrl(dav, "fam-1", "dev-1"),
        )
        // 按家庭隔离：不同 familyId 目录不同；两台手机配对后 familyId 相同才会落到一起
        assertEquals(
            "https://dav.jianguoyun.com/dav/familyledger/fam-2/dev-1.jsonl",
            SyncPaths.journalUrl(dav, "fam-2", "dev-1"),
        )
    }

    @Test
    fun `从文件名还原设备ID`() {
        assertEquals("dev-1", SyncPaths.deviceIdOfFileName("dev-1.jsonl"))
        assertEquals("dev-1", SyncPaths.deviceIdOfFileName("/dav/familyledger/fam/dev-1.jsonl"))
        assertNull(SyncPaths.deviceIdOfFileName("目录名"))
        assertNull(SyncPaths.deviceIdOfFileName(".hidden"))
    }

    @Test
    fun `PROPFIND结果能解析出文件名`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/familyledger/fam/</D:href></D:response>
              <D:response><D:href>/dav/familyledger/fam/dev-1.jsonl</D:href></D:response>
              <D:response><D:href>https://dav.jianguoyun.com/dav/familyledger/fam/dev-2.jsonl</D:href></D:response>
              <D:response><D:href>/dav/familyledger/fam/dev%2D3.jsonl</D:href></D:response>
              <D:response><D:href>/dav/familyledger/fam/%E8%B4%A6%E6%9C%AC.jsonl</D:href></D:response>
            </D:multistatus>
        """.trimIndent()

        val files = WebDavTransport.parseHrefs(xml).filter { it.endsWith(".jsonl") }
        assertEquals(listOf("dev-1.jsonl", "dev-2.jsonl", "dev-3.jsonl", "账本.jsonl"), files)
    }

    @Test
    fun `错误文案是中文且指出原因`() {
        assertTrue(SyncErrors.translate(UnknownHostException("dav.example.com")).contains("同步服务器地址"))
        assertTrue(SyncErrors.translate(java.net.SocketTimeoutException("timeout")).contains("超时"))
        assertTrue(SyncErrors.translate(SyncException("未配置")).contains("未配置"))
        assertTrue(SyncErrors.http(401, "url").contains("认证失败"))
        assertTrue(SyncErrors.http(409, "url").contains("409"))
        assertTrue(SyncErrors.http(503, "url").contains("暂时不可用"))
    }
}
