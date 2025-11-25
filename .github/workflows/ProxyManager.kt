package com.proxy.echlib

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

class ProxyManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "ProxyManager"
        
        @Volatile
        private var instance: ProxyManager? = null
        
        fun getInstance(context: Context): ProxyManager {
            return instance ?: synchronized(this) {
                instance ?: ProxyManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wsServer: WebSocketServer? = null
    private var tcpForwarder: TCPForwarder? = null
    private var proxyServer: ProxyServer? = null
    private var echConfig: ECHConfig? = null
    
    data class ServerConfig(
        val listenAddr: String,
        val forwardAddr: String? = null,
        val ipAddr: String? = null,
        val certFile: String? = null,
        val keyFile: String? = null,
        val token: String? = null,
        val cidrs: String = "0.0.0.0/0,::/0",
        val dnsServer: String = "119.29.29.29:53",
        val echDomain: String = "cloudflare-ech.com",
        val connectionNum: Int = 3
    )
    
    /**
     * 启动 WebSocket 服务端
     */
    fun startWebSocketServer(config: ServerConfig, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                wsServer = WebSocketServer(config, context)
                wsServer?.start()
                withContext(Dispatchers.Main) {
                    callback(true, "WebSocket 服务端已启动")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动 WebSocket 服务端失败", e)
                withContext(Dispatchers.Main) {
                    callback(false, "启动失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 启动 TCP 转发客户端 (带 ECH)
     */
    fun startTCPClient(config: ServerConfig, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                // 预先获取 ECH 配置
                echConfig = ECHHelper.prepareECH(config.dnsServer, config.echDomain)
                if (echConfig == null) {
                    withContext(Dispatchers.Main) {
                        callback(false, "获取 ECH 配置失败")
                    }
                    return@launch
                }
                
                tcpForwarder = TCPForwarder(config, echConfig!!, context)
                tcpForwarder?.start()
                
                withContext(Dispatchers.Main) {
                    callback(true, "TCP 客户端已启动")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动 TCP 客户端失败", e)
                withContext(Dispatchers.Main) {
                    callback(false, "启动失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 启动代理服务器 (SOCKS5 + HTTP)
     */
    fun startProxyServer(config: ServerConfig, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                // 预先获取 ECH 配置
                echConfig = ECHHelper.prepareECH(config.dnsServer, config.echDomain)
                if (echConfig == null) {
                    withContext(Dispatchers.Main) {
                        callback(false, "获取 ECH 配置失败")
                    }
                    return@launch
                }
                
                proxyServer = ProxyServer(config, echConfig!!, context)
                proxyServer?.start()
                
                withContext(Dispatchers.Main) {
                    callback(true, "代理服务器已启动")
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动代理服务器失败", e)
                withContext(Dispatchers.Main) {
                    callback(false, "启动失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 停止所有服务
     */
    fun stopAll() {
        scope.launch {
            wsServer?.stop()
            tcpForwarder?.stop()
            proxyServer?.stop()
            
            wsServer = null
            tcpForwarder = null
            proxyServer = null
            echConfig = null
            
            Log.i(TAG, "所有服务已停止")
        }
    }
    
    /**
     * 获取系统代理配置 (for VPN mode)
     */
    fun getProxyConfig(): Proxy? {
        val config = proxyServer?.getListenAddress() ?: return null
        return try {
            val (host, port) = config.split(":")
            Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port.toInt()))
        } catch (e: Exception) {
            Log.e(TAG, "解析代理配置失败", e)
            null
        }
    }
    
    /**
     * 检查服务状态
     */
    fun isRunning(): Boolean {
        return wsServer?.isRunning() == true || 
               tcpForwarder?.isRunning() == true || 
               proxyServer?.isRunning() == true
    }
    
    /**
     * 获取连接统计
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "wsServer" to (wsServer?.getStats() ?: emptyMap()),
            "tcpForwarder" to (tcpForwarder?.getStats() ?: emptyMap()),
            "proxyServer" to (proxyServer?.getStats() ?: emptyMap())
        )
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopAll()
        scope.cancel()
    }
}

// ============================================================
// ECHConfig.kt - ECH 配置数据类
// ============================================================
package com.proxy.echlib

data class ECHConfig(
    val echConfigList: ByteArray,
    val domain: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(ttlMs: Long = 3600000): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as ECHConfig
        
        if (!echConfigList.contentEquals(other.echConfigList)) return false
        if (domain != other.domain) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = echConfigList.contentHashCode()
        result = 31 * result + domain.hashCode()
        return result
    }
}