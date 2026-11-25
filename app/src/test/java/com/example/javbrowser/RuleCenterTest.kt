package com.example.javbrowser

import org.junit.Assert.*
import org.junit.Test

/** 规则刷新：镜像滞后时不能把直连的更新挡在外面。 */
class RuleCenterTest {
    private fun rules(version: Int) = RuleSet(version, "2026-11-25T00:00:00Z", listOf(
        SiteRule(host = "example.invalid", type = "regex-m3u8")
    ))

    @Test fun staleMirrorDoesNotBlockNewerDirectRules() {
        // 镜像 v1、直连 v3，当前 v2：要取到 v3，不能因镜像旧就当已是最新。
        val best = RuleCenter.pickNewest(
            listOf(rules(1) to "v1", rules(3) to "v3"), currentVersion = 2)
        assertNotNull(best)
        assertEquals(3, best!!.first.version)
        assertEquals("v3", best.second)
    }

    @Test fun noNewerVersionReturnsNull() {
        assertNull(RuleCenter.pickNewest(
            listOf(rules(1) to "v1", rules(2) to "v2"), currentVersion = 2))
        assertNull(RuleCenter.pickNewest(emptyList(), currentVersion = 2))
    }

    @Test fun newestWinsRegardlessOfOrder() {
        val best = RuleCenter.pickNewest(
            listOf(rules(3) to "v3", rules(1) to "v1"), currentVersion = 0)
        assertEquals(3, best!!.first.version)
    }
}
