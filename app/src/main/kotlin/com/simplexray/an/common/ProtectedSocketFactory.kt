package com.simplexray.an.common

import com.simplexray.an.service.TProxyService
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * A [SocketFactory] that calls [TProxyService.protectSocket] on every newly created socket.
 *
 * When the VPN service is active, [TProxyService.protectSocket] marks the socket's file
 * descriptor as "protected" via [android.net.VpnService.protect], so its traffic is routed
 * through the real network interface instead of the TUN tunnel.  When the service is not
 * running the call is a no-op and the socket behaves like an ordinary socket.
 *
 * Pass an instance to [okhttp3.OkHttpClient.Builder.socketFactory] to make all OkHttp
 * connections bypass the active VPN tunnel.
 */
class ProtectedSocketFactory(
    private val delegate: SocketFactory = getDefault()
) : SocketFactory() {

    /**
     * Protect [socket] and return it.  If [delegate]'s `createSocket` throws, this method
     * never runs and the exception propagates normally – there is no socket to protect.
     */
    private fun protect(socket: Socket): Socket {
        TProxyService.protectSocket(socket)
        return socket
    }

    override fun createSocket(): Socket = protect(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        protect(delegate.createSocket(host, port))

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = protect(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = protect(delegate.createSocket(address, port, localAddress, localPort))
}
