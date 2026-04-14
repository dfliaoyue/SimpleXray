package com.simplexray.an.common

import android.util.Log
import com.google.protobuf.ByteString
import com.xray.app.proxyman.PortList
import com.xray.app.proxyman.PortRange
import com.xray.app.proxyman.ReceiverConfig
import com.xray.app.proxyman.command.AddInboundRequest
import com.xray.app.proxyman.command.HandlerServiceGrpc
import com.xray.app.proxyman.command.RemoveInboundRequest
import com.xray.common.net.IPOrDomain
import com.xray.common.serial.TypedMessage
import com.xray.core.InboundHandlerConfig
import com.xray.proxy.socks.AuthType
import com.xray.proxy.socks.ServerConfig
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.TimeUnit

private const val TAG = "HandlerServiceClient"

class HandlerServiceClient(private val channel: ManagedChannel) : Closeable {

    private val stub = HandlerServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)

    /**
     * Ask Xray to create a SOCKS5 inbound on [port], bound to 127.0.0.1.
     * Uses PASSWORD auth with [username]/[password] when both are non-empty,
     * otherwise falls back to NO_AUTH.  Returns true if the RPC call succeeded.
     */
    suspend fun addSocksInbound(
        tag: String,
        port: Int,
        username: String,
        password: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val portRange = PortRange.newBuilder().setFrom(port).setTo(port).build()
            val portList = PortList.newBuilder().addRange(portRange).build()
            val listenAddr = IPOrDomain.newBuilder()
                .setIp(ByteString.copyFrom(byteArrayOf(127, 0, 0, 1))) // 127.0.0.1
                .build()
            val receiverConfig = ReceiverConfig.newBuilder()
                .setPortList(portList)
                .setListen(listenAddr)
                .build()

            val socksConfig = if (username.isNotEmpty() && password.isNotEmpty()) {
                ServerConfig.newBuilder()
                    .setAuthType(AuthType.PASSWORD)
                    .putAccounts(username, password)
                    .build()
            } else {
                ServerConfig.newBuilder()
                    .setAuthType(AuthType.NO_AUTH)
                    .build()
            }

            val inboundConfig = InboundHandlerConfig.newBuilder()
                .setTag(tag)
                .setReceiverSettings(
                    TypedMessage.newBuilder()
                        .setType("xray.app.proxyman.ReceiverConfig")
                        .setValue(receiverConfig.toByteString())
                        .build()
                )
                .setProxySettings(
                    TypedMessage.newBuilder()
                        .setType("xray.proxy.socks.ServerConfig")
                        .setValue(socksConfig.toByteString())
                        .build()
                )
                .build()

            stub.addInbound(AddInboundRequest.newBuilder().setInbound(inboundConfig).build())
            true
        }.getOrElse { e ->
            Log.e(TAG, "addSocksInbound failed (tag=$tag, port=$port)", e)
            false
        }
    }

    /** Ask Xray to remove the inbound with the given [tag]. Returns true on success. */
    suspend fun removeInbound(tag: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            stub.removeInbound(RemoveInboundRequest.newBuilder().setTag(tag).build())
            true
        }.getOrElse { e ->
            Log.e(TAG, "removeInbound failed (tag=$tag)", e)
            false
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        fun create(host: String, port: Int): HandlerServiceClient {
            val channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
            return HandlerServiceClient(channel)
        }
    }
}
