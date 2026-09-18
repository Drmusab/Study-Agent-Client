package com.studyagent.client.network

import com.studyagent.client.core.models.ProfileValidator
import com.studyagent.client.core.models.ServerProfile
import org.junit.Assert.*
import org.junit.Test

class ProfileValidatorTest {

    @Test
    fun validHosts() {
        assertTrue(ProfileValidator.isValidHost("192.168.1.100"))
        assertTrue(ProfileValidator.isValidHost("10.0.0.1"))
        assertTrue(ProfileValidator.isValidHost("my-pc.local"))
        assertTrue(ProfileValidator.isValidHost("100.64.0.1"))
        assertTrue(ProfileValidator.isValidHost("my-machine.tail-scale.ts.net"))
        assertTrue(ProfileValidator.isValidHost("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
    }

    @Test
    fun invalidHosts() {
        assertFalse(ProfileValidator.isValidHost(""))
        assertFalse(ProfileValidator.isValidHost("   "))
        assertFalse(ProfileValidator.isValidHost("999.999.999.999"))
        assertFalse(ProfileValidator.isValidHost("host with spaces"))
    }

    @Test
    fun tailscaleDetection() {
        assertTrue(ProfileValidator.isTailscaleIp("100.64.0.1"))
        assertTrue(ProfileValidator.isTailscaleIp("100.127.255.255"))
        assertFalse(ProfileValidator.isTailscaleIp("100.63.0.1"))
        assertFalse(ProfileValidator.isTailscaleIp("192.168.1.100"))

        assertTrue(ProfileValidator.isMagicDns("my-pc.tail-scale.ts.net"))
        assertTrue(ProfileValidator.isMagicDns("machine.tailscale.com"))
        assertFalse(ProfileValidator.isMagicDns("192.168.1.100"))

        assertTrue(ProfileValidator.isTailscale("100.64.0.1"))
        assertTrue(ProfileValidator.isTailscale("my-pc.ts.net"))
    }

    @Test
    fun emulatorDetection() {
        assertTrue(ProfileValidator.isEmulatorAddress("10.0.2.2"))
        assertFalse(ProfileValidator.isEmulatorAddress("192.168.1.100"))
    }

    @Test
    fun loopbackWarning() {
        assertTrue(ProfileValidator.isLoopbackWarning("127.0.0.1"))
        assertTrue(ProfileValidator.isLoopbackWarning("localhost"))
        assertFalse(ProfileValidator.isLoopbackWarning("192.168.1.100"))
    }

    @Test
    fun profileValidation() {
        val valid = ServerProfile(name = "Home PC", host = "192.168.1.100", port = 8765)
        assertTrue(ProfileValidator.validate(valid).isValid)

        val invalidPort = ServerProfile(name = "Test", host = "192.168.1.100", port = 99999)
        assertFalse(ProfileValidator.validate(invalidPort).isValid)

        val blankName = ServerProfile(name = "", host = "192.168.1.100", port = 8765)
        assertFalse(ProfileValidator.validate(blankName).isValid)

        val invalidPath = ServerProfile(name = "Test", host = "192.168.1.100", port = 8765, path = "ws")
        assertFalse(ProfileValidator.validate(invalidPath).isValid)
    }

    @Test
    fun localNetworkDetection() {
        assertTrue(ProfileValidator.isLocalNetwork("192.168.1.100"))
        assertTrue(ProfileValidator.isLocalNetwork("10.0.0.1"))
        assertTrue(ProfileValidator.isLocalNetwork("10.0.2.2"))
        assertFalse(ProfileValidator.isLocalNetwork("100.64.0.1"))
    }
}
