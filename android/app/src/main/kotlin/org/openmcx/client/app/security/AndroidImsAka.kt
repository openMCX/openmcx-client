//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

package org.openmcx.client.app.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.net.Network
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openmcx.client.security.AkaOutcome
import org.openmcx.client.security.CardAka
import org.openmcx.client.security.EspKeys
import org.openmcx.client.sip.Connection
import org.openmcx.client.sip.ImsAkaPlatform
import org.openmcx.client.sip.SaReservation
import org.openmcx.client.sip.SaSet
import org.openmcx.client.sip.SaSetParams
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The card of IMS AKA: the ISIM of the subscription whose card gives this app carrier privileges (its ARA-M rule
 * names the app's signing certificate). TS 31.103 clause 7.1.2, AUTHENTICATE in the IMS AKA security context, through
 * TelephonyManager.getIccAuthentication; the app never sees K or OPc.
 */
class IsimCard(private val context: Context) : CardAka {
    /** The card's TelephonyManager and its SIM slot (0-based), or null without a subscription that gives carrier
     *  privileges. */
    fun privileged(): Pair<TelephonyManager, Int>? {
        val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        val tm = context.getSystemService(TelephonyManager::class.java)
        return subs.map { tm.createForSubscriptionId(it.subscriptionId) to it.simSlotIndex }.firstOrNull { it.first.hasCarrierPrivileges() }
    }

    fun telephony(): TelephonyManager? = privileged()?.first

    /** The ISIM's identities (TS 31.103 clauses 4.2.2 to 4.2.4). */
    data class Identities(val impi: String, val impu: String, val domain: String)

    /**
     * EF_IMPI, EF_IMPU (its first record) and EF_DOMAIN of the ISIM, read over a logical channel (which only an app with
     * carrier privileges may open); null when the ISIM cannot be opened or a file is empty.
     */
    fun identities(tm: TelephonyManager): Identities? {
        val channel = tm.iccOpenLogicalChannel(ISIM_AID, 0x04)
        if (channel.status != android.telephony.IccOpenLogicalChannelResponse.STATUS_NO_ERROR) return null
        try {
            fun read(fid: String, record: Boolean): String? {
                // SELECT by file identifier without response data (P2 0x0C), then READ RECORD 1 or READ BINARY.
                val select = tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xA4, 0x00, 0x0C, 0x02, fid)
                if (select?.endsWith("9000") != true) return null
                val data = if (record) tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xB2, 0x01, 0x04, 0x00, "")
                           else tm.iccTransmitApduLogicalChannel(channel.channel, 0x00, 0xB0, 0x00, 0x00, 0x00, "")
                return data?.let { tlv80(it) }?.takeIf { it.isNotEmpty() }
            }
            return Identities(read("6F02", false) ?: return null, read("6F04", true) ?: return null, read("6F03", false) ?: return null)
        } finally {
            tm.iccCloseLogicalChannel(channel.channel)
        }
    }

    private companion object {
        /** The ISIM's application identifier (ETSI TS 101 220 annex E: RID A000000087, application 1004). */
        const val ISIM_AID = "A0000000871004FFFFFFFF8907090000"

        /** The text of a TS 31.103 clause 4.2.2 to 4.2.4 TLV (tag 0x80, one length octet) before the status word. */
        fun tlv80(response: String): String? {
            if (response.length < 8 || !response.endsWith("9000")) return null
            val hex = response.dropLast(4)
            val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            if (bytes[0] != 0x80.toByte()) return null
            val n = bytes[1].toInt() and 0xff
            return if (n + 2 <= bytes.size) String(bytes, 2, n, Charsets.UTF_8) else null
        }
    }

    override suspend fun authenticate(rand: ByteArray, autn: ByteArray): AkaOutcome = withContext(Dispatchers.IO) {
        val tm = telephony() ?: throw IllegalStateException("no card with carrier privileges")
        // TS 31.102 clause 7.1.2.1: L RAND, RAND, L AUTN, AUTN.
        val data = byteArrayOf(rand.size.toByte()) + rand + byteArrayOf(autn.size.toByte()) + autn
        val answer = tm.getIccAuthentication(TelephonyManager.APPTYPE_ISIM, TelephonyManager.AUTHTYPE_EAP_AKA,
            Base64.encodeToString(data, Base64.NO_WRAP))
        // The card's refusal of the network (MAC failure, status word 9862) reaches the app only as a missing answer.
        val r = answer?.let { Base64.decode(it, Base64.DEFAULT) } ?: return@withContext AkaOutcome.MacFailure
        fun field(at: Int): Pair<ByteArray, Int> = r.copyOfRange(at + 1, at + 1 + (r[at].toInt() and 0xff)) to at + 1 + (r[at].toInt() and 0xff)
        when (r.firstOrNull()?.toInt()?.and(0xff)) {
            // Successful 3G context: DB, L RES, RES, L CK, CK, L IK, IK.
            0xDB -> { val (res, a) = field(1); val (ck, b) = field(a); val (ik, _) = field(b); AkaOutcome.Success(res, ck, ik) }
            // Synchronisation failure: DC, L AUTS, AUTS.
            0xDC -> AkaOutcome.SyncFailure(field(1).first)
            else -> AkaOutcome.MacFailure
        }
    }
}

/** A TCP connection on a socket the platform opened (a java.net.Socket or a raw descriptor). */
private class SocketConnection(
    override val input: InputStream, override val output: OutputStream,
    override val localHost: String, override val localPort: Int, private val onClose: () -> Unit,
) : Connection {
    override val transport = "TCP"
    override fun close() = onClose()
}

/**
 * ImsAkaPlatform on Android: the default network's address, the ISIM ([IsimCard]) and the four transport-mode SAs of
 * TS 33.203 clause 7.1 through IpSecManager, applied per socket: the client socket from port_uc carries the
 * port_uc -> port_ps (spi_ps) and port_ps -> port_uc (spi_uc) SAs, the listening socket of port_us the port_pc ->
 * port_us (spi_us) and port_us -> port_pc (spi_pc) ones, which each connection it accepts keeps. The ESP keys are
 * those of TS 33.203 annex I ([EspKeys]); AES-GMAC is not among IpSecManager's algorithms (SecAgree.SUPPORTED).
 */
class AndroidImsAkaPlatform(private val context: Context, override val card: CardAka = IsimCard(context)) : ImsAkaPlatform {
    private val ipsec = context.getSystemService(IpSecManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var listener: FileDescriptor? = null
    // The set in use: a connection accepted on port_us carries the listener's transforms of that moment, those of the
    // newest set, and belongs to it (not to the set that opened the listener).
    @Volatile private var current: Pair<SaSetParams, (Connection) -> Unit>? = null
    // Every accepted connection still open, closed at the latest with the listener.
    private val accepted = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<FileDescriptor, Boolean>())

    private fun network(): Network = connectivity.activeNetwork ?: throw IllegalStateException("no network")

    override fun localAddress(): InetAddress {
        val addresses = connectivity.getLinkProperties(network())?.linkAddresses.orEmpty().map { it.address }
        return addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: addresses.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?: throw IllegalStateException("no address on the network")
    }

    private inner class Reservation(local: InetAddress) : SaReservation {
        // The UE's inbound SPIs (TS 33.203 clause 7.1: spi_uc, spi_us, chosen by the UE).
        val uc: IpSecManager.SecurityParameterIndex = ipsec.allocateSecurityParameterIndex(local)
        val us: IpSecManager.SecurityParameterIndex = ipsec.allocateSecurityParameterIndex(local)
        // The client socket, bound now so that port_uc is known for the Security-Client.
        val socket = Socket().also { network().bindSocket(it); it.reuseAddress = true; it.bind(InetSocketAddress(local, 0)) }
        override val spiUc = uc.spi.toLong() and 0xffffffffL
        override val spiUs = us.spi.toLong() and 0xffffffffL
        override val portUc = socket.localPort
        @Volatile var used = false
        override fun close() {
            if (!used) runCatching { socket.close() }
            runCatching { uc.close() }; runCatching { us.close() }
        }
    }

    override fun reserve(local: InetAddress): SaReservation = Reservation(local)

    override suspend fun connectUnprotected(local: InetAddress, pcscf: InetAddress, port: Int): Connection = withContext(Dispatchers.IO) {
        val socket = Socket()
        network().bindSocket(socket)
        socket.bind(InetSocketAddress(local, 0))
        socket.connect(InetSocketAddress(pcscf, port), 10_000)
        SocketConnection(socket.getInputStream(), socket.getOutputStream(), local.hostAddress!!, socket.localPort) { socket.close() }
    }

    private fun transform(source: InetAddress, spi: IpSecManager.SecurityParameterIndex, p: SaSetParams, toUe: Boolean, fromServer: Boolean): IpSecTransform {
        val b = IpSecTransform.Builder(context)
        val keys = p.keys
        when (p.ealg.lowercase()) {
            "aes-gcm", "aes-gcm-us" -> b.setAuthenticatedEncryption(IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM,
                keys.encryption + EspKeys.saSalt(keys.salt, p.ealg, toUe, fromServer), 128))
            else -> {
                when (p.alg.lowercase()) {
                    "hmac-sha2-256-128" -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, keys.integrity, 128))
                    "hmac-sha-1-96" -> b.setAuthentication(IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA1, keys.integrity, 96))
                    else -> throw IllegalArgumentException("integrity algorithm ${p.alg}")
                }
                if (p.ealg.equals("aes-cbc", true)) b.setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, keys.encryption))
            }
        }
        return b.buildTransportModeTransform(source, spi)
    }

    override suspend fun open(reservation: SaReservation, params: SaSetParams, onIncoming: (Connection) -> Unit): SaSet = withContext(Dispatchers.IO) {
        val r = reservation as Reservation
        val p = params
        // Outbound SPIs: the P-CSCF's (spi_ps, spi_pc), reserved for its address. Each allocation is closed again if a
        // later step fails: an SPI or transform left open holds the SPI until it is collected, and the P-CSCF reuses
        // its SPIs for later sets.
        val spis = mutableListOf<IpSecManager.SecurityParameterIndex>()
        val transforms = mutableListOf<IpSecTransform>()
        try {
            val ps = ipsec.allocateSecurityParameterIndex(p.pcscf, p.spiPs.toInt()).also { spis += it }
            val pc = ipsec.allocateSecurityParameterIndex(p.pcscf, p.spiPc.toInt()).also { spis += it }
            val ueToPs = transform(p.local, ps, p, toUe = false, fromServer = false).also { transforms += it }
            val psToUe = transform(p.pcscf, r.uc, p, toUe = true, fromServer = true).also { transforms += it }
            val pcToUs = transform(p.pcscf, r.us, p, toUe = true, fromServer = false).also { transforms += it }
            val usToPc = transform(p.local, pc, p, toUe = false, fromServer = true).also { transforms += it }
            // port_us: one listening socket for the registration's life; each new set gives it its transforms.
            val fd = listener ?: Os.socket(if (p.local is Inet4Address) OsConstants.AF_INET else OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0).also { fd ->
                network().bindSocket(fd)
                Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                Os.bind(fd, p.local, p.portUs)
                Os.listen(fd, 8)
                listener = fd
                thread(name = "ims-aka-port-us", isDaemon = true) { accept(fd) }
            }
            ipsec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_IN, pcToUs)
            ipsec.applyTransportModeTransform(fd, IpSecManager.DIRECTION_OUT, usToPc)
            current = p to onIncoming
            val socket = r.socket
            ipsec.applyTransportModeTransform(socket, IpSecManager.DIRECTION_OUT, ueToPs)
            ipsec.applyTransportModeTransform(socket, IpSecManager.DIRECTION_IN, psToUe)
            socket.connect(InetSocketAddress(p.pcscf, p.portPs), 10_000)
            r.used = true
            val client = SocketConnection(socket.getInputStream(), socket.getOutputStream(), p.local.hostAddress!!, socket.localPort) {
                runCatching { ipsec.removeTransportModeTransforms(socket) }; runCatching { socket.close() }
            }
            object : SaSet {
                override val params = p
                override val client = client
                override fun close() {
                    client.close()
                    transforms.forEach { runCatching { it.close() } }
                    spis.forEach { runCatching { it.close() } }
                }
            }
        } catch (e: Exception) {
            transforms.forEach { runCatching { it.close() } }
            spis.forEach { runCatching { it.close() } }
            throw e
        }
    }

    /**
     * The P-CSCF's connections to port_us (TS 33.203 clause 7.1, from port_pc), each a connection of its own, handed
     * to the set in use when it arrives.
     */
    private fun accept(fd: FileDescriptor) {
        while (listener === fd) {
            val socket = try { Os.accept(fd, null) } catch (e: Exception) { return }
            // Linger 0: a close (also by the process's end) ends the connection at once with an RST and leaves no
            // socket behind. A FIN_WAIT socket left on port_us would take the P-CSCF's next connection from the same
            // port_pc (a later set may get it) for itself: its SYN gets an ACK, not a SYN-ACK, and the connection
            // never comes up.
            runCatching { lingerZero(socket) }
            val set = current
            if (set == null) { abort(socket); continue }
            val (p, onIncoming) = set
            accepted += socket
            onIncoming(SocketConnection(FileInputStream(socket), FileOutputStream(socket), p.local.hostAddress!!, p.portUs) { abort(socket) })
        }
    }

    /**
     * SO_LINGER on, 0 s. The SDK has no public call for it (Os.setsockoptLinger is hidden). Os.setsockoptTimeval passes
     * a struct timeval, which on Android's little-endian ABIs begins with the bytes of struct linger { int l_onoff;
     * int l_linger; }: tv_sec 1, tv_usec 0 gives l_onoff 1, l_linger 0 (both 32-bit and 64-bit; the kernel reads the
     * first sizeof(struct linger) octets).
     */
    private fun lingerZero(socket: FileDescriptor) =
        Os.setsockoptTimeval(socket, OsConstants.SOL_SOCKET, OsConstants.SO_LINGER, StructTimeval.fromMillis(1000))

    /**
     * Its transforms removed, then closed. A socket that still names a set's SAs makes the kernel hold the SPI of its
     * outbound SA when it sends (seen on Android 16, also for the single RST of a linger-0 close), and the P-CSCF gives
     * that SPI to a later set; so no accepted connection may outlive its set, and none closes with its transforms on.
     * With linger 0 (set at accept) the close sends an RST, in clear (the P-CSCF drops it on its protected port and
     * closes its side when it deletes the set), and leaves no FIN_WAIT socket on port_us.
     */
    private fun abort(socket: FileDescriptor) {
        if (!accepted.remove(socket) && !socket.valid()) return
        runCatching { ipsec.removeTransportModeTransforms(socket) }
        runCatching { Os.close(socket) }
    }

    override fun release() {
        val fd = listener ?: return
        listener = null
        current = null
        accepted.toList().forEach { abort(it) }
        runCatching { ipsec.removeTransportModeTransforms(fd) }
        runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
        runCatching { Os.close(fd) }
    }
}
