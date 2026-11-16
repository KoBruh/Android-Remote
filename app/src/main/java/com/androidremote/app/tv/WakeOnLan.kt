package com.androidremote.app.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Wake-on-LAN: builds and broadcasts the magic packet that wakes the TV from networked standby. */
object WakeOnLan {

    private val PORTS = intArrayOf(9, 7)
    private const val REPEATS = 3
    private const val REPEAT_GAP_MS = 100L

    /** Parses "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff" or "AABBCCDDEEFF". Returns null if it is not a MAC address. */
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.trim().replace(":", "").replace("-", "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Formats a MAC as "AA:BB:CC:DD:EE:FF", or returns null if [mac] is not a MAC address. */
    fun normalizeMac(mac: String): String? =
        parseMac(mac)?.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    /** The magic packet: 6 x 0xFF followed by the MAC address 16 times (102 bytes). */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC must be 6 bytes" }
        val packet = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) mac.copyInto(packet, 6 + i * 6)
        return packet
    }

    /** Broadcast address of the IPv4 subnet that [address] with [prefixLength] belongs to. */
    fun broadcastAddress(address: ByteArray, prefixLength: Int): ByteArray {
        require(address.size == 4 && prefixLength in 0..32)
        val ip = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val hostMask = if (prefixLength == 32) 0 else (-1 ushr prefixLength)
        val broadcast = ip or hostMask
        return ByteArray(4) { (broadcast ushr (24 - it * 8)).toByte() }
    }

    /**
     * Sends the magic packet for [mac] to 255.255.255.255 and to every local subnet's broadcast
     * address, on ports 9 and 7, three times, 100 ms apart.
     *
     * @param bindSocket binds the socket to the Wi-Fi network, so the packet does not leave over mobile data.
     * @return false if nothing could be sent.
     */
    suspend fun send(mac: ByteArray, bindSocket: (DatagramSocket) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac)
        val targets = buildSet {
            add(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
            addAll(localBroadcastAddresses())
        }
        var sent = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                runCatching { bindSocket(socket) }
                repeat(REPEATS) { round ->
                    for (target in targets) for (port in PORTS) {
                        try {
                            socket.send(DatagramPacket(packet, packet.size, target, port))
                            sent = true
                        } catch (_: IOException) {
                            // One target failing (e.g. no route) must not stop the others.
                        }
                    }
                    if (round < REPEATS - 1) delay(REPEAT_GAP_MS)
                }
            }
        } catch (_: IOException) {
            // No socket: nothing sent.
        }
        sent
    }

    private fun localBroadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .filter { it.address is Inet4Address }
            .map { InetAddress.getByAddress(broadcastAddress(it.address.address, it.networkPrefixLength.toInt())) }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }
}
