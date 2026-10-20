package com.buildx.ai.llm

import org.junit.Assert
import org.junit.Test

class LlmConfigTest {
    @Test fun offByDefaultMeansNoConfig() {
        Assert.assertNull(LlmConfig.of(false, "https://x.example.com/api/assist", "t"))
    }

    @Test fun anHttpsAddressWorksAndIsTrimmed() {
        val c = LlmConfig.of(true, "  https://x.example.com/api/assist ", " tok ")!!
        Assert.assertEquals("https://x.example.com/api/assist", c.url)
        Assert.assertEquals("tok", c.token)
    }

    @Test fun plainHttpIsRefused() {
        Assert.assertNull(LlmConfig.of(true, "http://x.example.com/api/assist", "t"))
    }

    @Test fun nonsenseAddressesAreRefused() {
        for (bad in listOf("", "   ", "https://", "https:///path", "ftp://x.example.com", "javascript:alert(1)", "x.example.com/api", "https://exa mple.com", "https://" + "a".repeat(400))) {
            Assert.assertNull("should refuse: $bad", LlmConfig.of(true, bad, "t"))
        }
    }

    @Test fun aMissingTokenIsAllowedForOwnServers() {
        Assert.assertEquals("", LlmConfig.of(true, "https://x.example.com/a", "")!!.token)
    }
}
