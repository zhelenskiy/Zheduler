package com.zhelenskiy.zheduler.zheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which callers the rate limiter treats as the same caller.
 *
 * The whole per-address limit rests on this: an IPv6 subscriber is handed a /64 and can put a
 * different address on every request, so counting the full address would mean the limit never
 * fires at all. Compressed forms are the ones that matter in practice — a reverse proxy writes
 * addresses the short way, and that is the topology the forwarded-header option exists for.
 */
class RateLimitKeyTest {

    @Test
    fun `an IPv4 address is counted whole`() {
        assertEquals("203.0.113.7", rateLimitKeyFor("203.0.113.7"))
        assertEquals("127.0.0.1", rateLimitKeyFor("127.0.0.1"))
        assertNotEquals(rateLimitKeyFor("203.0.113.7"), rateLimitKeyFor("203.0.113.8"))
    }

    @Test
    fun `every address in one IPv6 slash 64 counts as the same caller`() {
        val first = rateLimitKeyFor("2001:0db8:85a3:0000:0000:8a2e:0370:7334")
        val second = rateLimitKeyFor("2001:0db8:85a3:0000:ffff:ffff:ffff:0001")
        assertEquals(first, second, "an attacker would get a fresh identity per request")
        assertEquals("2001:db8:85a3:0::/64", first)
    }

    @Test
    fun `two different IPv6 slash 64s are different callers`() {
        assertNotEquals(
            rateLimitKeyFor("2001:0db8:85a3:0001:0000:0000:0000:0001"),
            rateLimitKeyFor("2001:0db8:85a3:0002:0000:0000:0000:0001"),
        )
    }

    @Test
    fun `a compressed address collapses to the same key as its expanded form`() {
        // The case that matters: a proxy writes the short form, and reading the groups as written
        // would give the two spellings different keys.
        assertEquals(
            rateLimitKeyFor("2001:db8:85a3:0:0:8a2e:370:7334"),
            rateLimitKeyFor("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
        )
    }

    @Test
    fun `a zero run starting inside the prefix still collapses`() {
        // Subnet zero is the first subnet of every allocation, so this is the ordinary case rather
        // than an exotic one — and it is the one that used to get a key per address.
        assertEquals(
            rateLimitKeyFor("2001:db8:abcd::1"),
            rateLimitKeyFor("2001:db8:abcd::2"),
        )
        assertEquals("2001:db8:abcd:0::/64", rateLimitKeyFor("2001:db8:abcd::1"))
        assertEquals(
            rateLimitKeyFor("2001:db8:abcd::1"),
            rateLimitKeyFor("2001:db8:abcd:0:ffff:ffff:ffff:ffff"),
        )
    }

    @Test
    fun `a zero run spanning the whole prefix still collapses`() {
        assertEquals(rateLimitKeyFor("::1"), rateLimitKeyFor("::2"))
        assertEquals("0:0:0:0::/64", rateLimitKeyFor("::1"))
    }

    @Test
    fun `an IPv4-mapped address is counted as the IPv4 caller it is`() {
        // Collapsing these to a shared /64 would put every IPv4 caller behind such a stack in one
        // bucket, where one abuser limits out all the rest.
        assertEquals("203.0.113.7", rateLimitKeyFor("::ffff:203.0.113.7"))
        assertEquals("203.0.113.7", rateLimitKeyFor("0:0:0:0:0:ffff:203.0.113.7"))
        assertNotEquals(
            rateLimitKeyFor("::ffff:203.0.113.7"),
            rateLimitKeyFor("::ffff:203.0.113.8"),
        )
    }

    @Test
    fun `a port is not part of who the caller is`() {
        // A port belongs to one connection. Keeping it would give every TCP connection its own
        // key, and the limiter would never see two requests as the same caller — which is how the
        // forwarded-header forms that carry a source port would walk straight through it.
        assertEquals("203.0.113.7", rateLimitKeyFor("203.0.113.7:4711"))
        assertEquals(
            rateLimitKeyFor("203.0.113.7:4711"),
            rateLimitKeyFor("203.0.113.7:52000"),
        )
        assertEquals(
            rateLimitKeyFor("[2001:db8:abcd::1]:443"),
            rateLimitKeyFor("2001:db8:abcd::1"),
        )
    }

    @Test
    fun `a bracketed address is read the same as a bare one`() {
        assertEquals(
            rateLimitKeyFor("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
            rateLimitKeyFor("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]"),
        )
    }

    @Test
    fun `case does not make a second caller out of one`() {
        assertEquals(
            rateLimitKeyFor("2001:0DB8:85A3:0000:0000:8A2E:0370:7334"),
            rateLimitKeyFor("2001:0db8:85a3:0000:0000:8a2e:0370:7334"),
        )
    }

    @Test
    fun `something that is not an address this code can read is counted whole`() {
        // Better one key per odd string than one key for all of them, which would let a malformed
        // address share a bucket with everybody else's.
        assertEquals("1:2:3:4:5:6:7:8:9", rateLimitKeyFor("1:2:3:4:5:6:7:8:9"))
        assertNotEquals(rateLimitKeyFor("1:2:3"), rateLimitKeyFor("4:5:6"))
    }
}
