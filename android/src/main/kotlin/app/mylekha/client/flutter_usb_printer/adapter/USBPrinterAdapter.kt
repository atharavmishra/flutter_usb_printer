package app.mylekha.client.flutter_usb_printer.adapter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset


class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null


    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbInterface: UsbInterface? = null
    private var mEndPointIn: UsbEndpoint? = null
    private var mEndPointOut: UsbEndpoint? = null


    private val ACTION_USB_PERMISSION = "app.mylekha.client.flutter_usb_printer.USB_PERMISSION"


    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this;
        }
        return mInstance
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (usbDevice == null) {
                        Log.e(LOG_TAG, "usbDevice is null in USB permission broadcast")
                        return
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success to grant permission for device ${usbDevice.deviceId}, vendor_id: ${usbDevice.vendorId} product_id: ${usbDevice.productId}"
                        )
                        mUsbDevice = usbDevice
                    } else {
                        Toast.makeText(
                            context,
                            "User refused to give USB device permissions for ${usbDevice.deviceName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                if (mUsbDevice != null) {
                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG)
                        .show()
                    closeConnectionIfExists()
                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            mPermissionIndent = PendingIntent.getBroadcast(
                mContext!!, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            mPermissionIndent = PendingIntent.getBroadcast(
                mContext!!, 0, Intent(ACTION_USB_PERMISSION), 0
            )
        }
//        mPermissionIndent =
//            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter, 0x4)
        } else {
            mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        }
        //mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "USB Printer initialized")
    }


    fun closeConnectionIfExists() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
            mUsbDeviceConnection!!.close()
            mUsbInterface = null
            mEndPointIn = null
            mUsbDeviceConnection = null
        }
    }

    fun getDeviceList(): List<UsbDevice> {
        if (mUSBManager == null) {
            Toast.makeText(
                mContext, "USB Manager is not initialized while get device list", Toast.LENGTH_LONG
            ).show()
            return emptyList()
        }
        return ArrayList(mUSBManager!!.deviceList.values)
    }

    fun selectDevice(vendorId: Int, productId: Int): Boolean {
        if (mUsbDevice == null || mUsbDevice!!.vendorId != vendorId || mUsbDevice!!.productId != productId) {
            closeConnectionIfExists()
            val usbDevices = getDeviceList()
            for (usbDevice in usbDevices) {
                if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                    Log.v(
                        LOG_TAG,
                        "Request for device: vendor_id: " + usbDevice.vendorId + ", product_id: " + usbDevice.productId
                    )
                    closeConnectionIfExists()
                    mUSBManager!!.requestPermission(usbDevice, mPermissionIndent)
                    mUsbDevice = usbDevice
                    return true
                }
            }
            return false
        }
        return true
    }

    private fun openConnection(): Boolean {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Device is not initialized")
            return false
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        if (mUsbDeviceConnection != null) {
            Log.i(LOG_TAG, "USB Connection already connected")
            return true
        }

        val usbInterface = mUsbDevice!!.getInterface(0)
        var endPointIn: UsbEndpoint? = null
        var endPointOut: UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    endPointOut = ep
                } else if (ep.direction == UsbConstants.USB_DIR_IN) {
                    endPointIn = ep
                }
            }
        }

        if (endPointOut == null || endPointIn == null) {
            Log.e(LOG_TAG, "Could not find both IN and OUT endpoints")
            return false
        }

        val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
        if (usbDeviceConnection == null) {
            Log.e(LOG_TAG, "Failed to open USB connection")
            return false
        }

        return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
            mUsbInterface = usbInterface
            mUsbDeviceConnection = usbDeviceConnection
            mEndPointOut = endPointOut
            mEndPointIn = endPointIn
            Toast.makeText(mContext, "Device connected", Toast.LENGTH_SHORT).show()
            true
        } else {
            usbDeviceConnection.close()
            Log.e(LOG_TAG, "Failed to claim USB interface")
            false
        }
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "start to print text")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                val b = mUsbDeviceConnection!!.bulkTransfer(
                    mEndPointOut, bytes, bytes.size, 100000
                )
                Log.i(LOG_TAG, "Return Status: b-->$b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun printRawText(data: String): Boolean {
        Log.v(LOG_TAG, "start to print raw data $data")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val b = mUsbDeviceConnection!!.bulkTransfer(
                    mEndPointOut, bytes, bytes.size, 100000
                )
                Log.i(LOG_TAG, "Return Status: $b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun write(bytes: ByteArray): Boolean {
        Log.v(LOG_TAG, "start to print raw data $bytes")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val b = mUsbDeviceConnection!!.bulkTransfer(
                    mEndPointOut, bytes, bytes.size, 100000
                )
                Log.i(LOG_TAG, "Return Status: $b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun read(): ByteArray? {
        Log.v(LOG_TAG, "Start reading data")
        val isConnected = openConnection()

        if (!isConnected) {
            Log.v(LOG_TAG, "Failed to connect to device for reading")
            return null
        }

        if (mEndPointIn == null) {
            Log.e(LOG_TAG, "IN endpoint is not initialized")
            return null
        }

        if (mUsbDeviceConnection == null) {
            Log.e(LOG_TAG, "USB device connection is null")
            return null
        }

        val buffer = ByteArray(mEndPointIn!!.maxPacketSize)
        val bytesRead = mUsbDeviceConnection!!.bulkTransfer(
            mEndPointIn, buffer, buffer.size, 5000 // timeout in ms
        )

        return if (bytesRead >= 0) {
            Log.i(LOG_TAG, "Bytes read: $bytesRead")
            buffer.copyOf(bytesRead)
        } else {
            Log.e(LOG_TAG, "Failed to read data, bulkTransfer returned: $bytesRead")
            null
        }
    }
}