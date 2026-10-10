package com.recap.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

object RecapperBle {
    // Must match firmware/src/main.cpp
    val SERVICE: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val CONTROL: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val EVENT: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val SEP = '\u001f'
}

enum class LinkState { Idle, Connecting, Ready, Failed }

/** GATT connection to the Recapper in setup mode. Commands out via CONTROL, events in via EVENT notifications. */
@SuppressLint("MissingPermission")
class RecapperLink(private val ctx: Context) {
    val state = MutableStateFlow(LinkState.Idle)
    val events = MutableSharedFlow<String>(extraBufferCapacity = 128)

    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null

    fun connect(mac: String) {
        close()
        state.value = LinkState.Connecting
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
        gatt = adapter.getRemoteDevice(mac).connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        gatt?.close()
        gatt = null
        control = null
        state.value = LinkState.Idle
    }

    fun send(vararg parts: String): Boolean {
        val g = gatt ?: return false
        val c = control ?: return false
        val bytes = parts.joinToString(RecapperBle.SEP.toString()).toByteArray()
        return g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
            BluetoothStatusCodes.SUCCESS
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                state.value = if (state.value == LinkState.Ready) LinkState.Idle else LinkState.Failed
                g.close()
                if (gatt === g) { gatt = null; control = null }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(RecapperBle.SERVICE)
            val ctrl = svc?.getCharacteristic(RecapperBle.CONTROL)
            val evt = svc?.getCharacteristic(RecapperBle.EVENT)
            val cccd = evt?.getDescriptor(RecapperBle.CCCD)
            if (ctrl == null || evt == null || cccd == null) { state.value = LinkState.Failed; return }
            control = ctrl
            g.setCharacteristicNotification(evt, true)
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            state.value = if (status == BluetoothGatt.GATT_SUCCESS) LinkState.Ready else LinkState.Failed
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            events.tryEmit(String(value))
        }
    }
}

/** Android Companion Device Manager: the system finds the nearby Recapper and shows a one-tap confirmation sheet. */
object Pairing {
    fun request(ctx: Context, launch: (IntentSender) -> Unit, onMac: (String) -> Unit, onError: (String) -> Unit) {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(RecapperBle.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        cdm.associate(request, ctx.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)
            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                associationInfo.deviceMacAddress?.let { onMac(it.toString().uppercase()) }
            }
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Couldn't find a Recapper nearby")
        })
    }

    fun macFromResult(ctx: Context, data: Intent?): String? {
        val info = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?: ctx.getSystemService(CompanionDeviceManager::class.java).myAssociations.lastOrNull()
        return info?.deviceMacAddress?.toString()?.uppercase()
    }
}
