package com.example.proxy

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private var proxyService: ProxyService? = null
    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val _stats = MutableStateFlow(ProxyServiceStats())
    val stats: StateFlow<ProxyServiceStats> = _stats.asStateFlow()

    private val _connectionCards = MutableStateFlow<Map<Long, ConnectionCardState>>(emptyMap())
    val connectionCards: StateFlow<Map<Long, ConnectionCardState>> = _connectionCards.asStateFlow()

    private val _logs = MutableStateFlow<List<FrameLog>>(emptyList())
    val logs: StateFlow<List<FrameLog>> = _logs.asStateFlow()

    private val _selectedProtocolFilter = MutableStateFlow<ProtocolType?>(null)
    val selectedProtocolFilter: StateFlow<ProtocolType?> = _selectedProtocolFilter.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? ProxyService.LocalBinder ?: return
            val service = localBinder.getService()
            proxyService = service
            _isBound.value = true

            viewModelScope.launch {
                service.stats.collect { _stats.value = it }
            }
            viewModelScope.launch {
                service.connectionCards.collect { _connectionCards.value = it }
            }
            viewModelScope.launch {
                service.logs.collect { _logs.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            _isBound.value = false
        }
    }

    init {
        startAndBindService()
    }

    fun startAndBindService() {
        val context = getApplication<Application>()
        ProxyService.start(context)
        val intent = Intent(context, ProxyService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopService() {
        val context = getApplication<Application>()
        if (_isBound.value) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore if already unbound
            }
            _isBound.value = false
        }
        val intent = Intent(context, ProxyService::class.java)
        context.stopService(intent)
        _stats.value = _stats.value.copy(isServiceRunning = false)
    }

    fun openNewStream(
        protocol: ProtocolType,
        endpoint: String,
        routingMode: RoutingMode = RoutingMode.IN_PROCESS_LOCAL,
        customUpstreamUrl: String? = null
    ) {
        proxyService?.openSession(protocol, endpoint, routingMode, customUpstreamUrl)
    }

    fun closeStream(streamId: Long) {
        proxyService?.closeSession(streamId)
    }

    fun removeStream(streamId: Long) {
        proxyService?.removeSession(streamId)
    }

    fun restartDefaultStreams() {
        proxyService?.initializeDefaultConnections()
    }

    fun sendWebSocketText(streamId: Long, text: String) {
        proxyService?.sendUpstreamFrame(streamId, text, isBinary = false)
    }

    fun sendWebSocketBinary(streamId: Long, hexString: String) {
        val sanitized = hexString.replace(" ", "").replace("0x", "")
        val bytes = try {
            sanitized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            byteArrayOf(0x01, 0x02, 0x03, 0x04)
        }
        proxyService?.sendUpstreamFrame(streamId, String(bytes, Charsets.ISO_8859_1), isBinary = true)
    }

    fun emitSocketIoEvent(streamId: Long, eventName: String, jsonPayload: String) {
        val payload = "42[\"$eventName\",$jsonPayload]"
        proxyService?.sendUpstreamFrame(streamId, payload, isBinary = false)
    }

    fun setProtocolFilter(protocol: ProtocolType?) {
        _selectedProtocolFilter.value = protocol
    }

    fun clearLogs() {
        proxyService?.clearLogs()
    }

    override fun onCleared() {
        super.onCleared()
        if (_isBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
