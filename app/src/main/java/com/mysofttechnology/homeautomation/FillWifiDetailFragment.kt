package com.mysofttechnology.homeautomation

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.android.volley.toolbox.StringRequest
import com.google.android.material.snackbar.Snackbar
import com.mysofttechnology.homeautomation.StartActivity.Companion.DEVICEIDSSET
import com.mysofttechnology.homeautomation.database.Device
import com.mysofttechnology.homeautomation.models.DeviceViewModel
import com.mysofttechnology.homeautomation.databinding.FragmentFillWifiDetailBinding
import com.mysofttechnology.homeautomation.utils.VolleySingleton
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.*

private const val TAG = "FillWifiDetailFragment"

class FillWifiDetailFragment : Fragment() {
    private var wifiManager: WifiManager? = null
    private var snackbar: Snackbar? = null
    private lateinit var loadingDialog: LoadingDialog

    private var btSocket: BluetoothSocket? = null
    private var sharedPref: SharedPreferences? = null

    private lateinit var ssid: String
    private val mUUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private lateinit var btDevice: String
    private lateinit var deviceId: String
    private lateinit var deviceIdsSet: HashSet<String>

    private var wifiSSIDList: ArrayList<String> = arrayListOf()
    private lateinit var listAdapter: ArrayAdapter<String>

    private var _binding: FragmentFillWifiDetailBinding? = null
    private val bind get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callback = requireActivity().onBackPressedDispatcher.addCallback(this) {
            snackbar?.dismiss()     // onBackPressedDispatcher

            wifiManager?.let {
                if (wifiManager!!.isWifiEnabled) {
                    wifiManager!!.isWifiEnabled = false
                    wifiManager!!.disconnect()
                }
            }
            Navigation.findNavController(requireView())
                .navigate(R.id.action_fillWifiDetailFragment_to_connectDeviceFragment)
//            val action =
//                FillWifiDetailFragmentDirections.actionFillWifiDetailFragmentToConnectDeviceFragment()
//            findNavController().navigate(action)
        }

        callback.isEnabled = true

        arguments?.let {
            btDevice = it.getString("btDevice").toString()
            deviceId = it.getString("deviceId").toString()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentFillWifiDetailBinding.inflate(inflater, container, false)
        listAdapter =
            ArrayAdapter(requireActivity(), android.R.layout.simple_list_item_1, wifiSSIDList)

        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return
        deviceIdsSet = sharedPref!!.getStringSet(DEVICEIDSSET, HashSet<String>()) as HashSet<String>
        Log.d(TAG, "onViewCreated: deviceIdsSet - $deviceIdsSet")

        loadingDialog = LoadingDialog()
        loadingDialog.isCancelable = false
        loadingDialog.show(parentFragmentManager, TAG)

        snackbar = Snackbar.make(bind.fwRootView,
            "Timeout! Make sure you are close to the ${getString(R.string.app_name)} device.",
            Snackbar.LENGTH_LONG).setAnchorView(bind.refreshFab)

        bind.backBtn.setOnClickListener {
            bind.backBtn.isEnabled = false
            Navigation.findNavController(it)
                .navigate(R.id.action_fillWifiDetailFragment_to_connectDeviceFragment)
        }

        bind.wifiLv.setOnItemClickListener { _, _, pos, _ ->
            loadingDialog.show(parentFragmentManager, TAG)
            ssid = wifiSSIDList[pos]
            Toast.makeText(requireActivity(), ssid, Toast.LENGTH_SHORT).show()
            if (btSocket != null && btSocket!!.isConnected) {
                sendSSIDToDevice()
                showWifiPasswordDialog()
            } else connectToBtDevice()
        }

        bind.refreshFab.setOnClickListener { getWifiDetails() }

        getWifiDetails()
        checkSettings()
//        connectToBtDevice()
    }

    private fun checkSettings() {
        val locationManager =
            requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val wifiManager = requireActivity().getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (ActivityCompat.checkSelfPermission(requireActivity(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(requireActivity(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            loadingDialog.dismiss()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
            return
        }

        if (!locationManager.isLocationEnabled) {
//            loadingDialog.dismiss()
            showTurnOnGPSDialog()
        } else {
            if (!wifiManager.isWifiEnabled) {
                showTurnOnWifiDialog()
            }
//            else connectToBtDevice()
        }
        loadingDialog.dismiss()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                // If request is cancelled, the result arrays are empty.
                if ((grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                            grantResults[1] == PackageManager.PERMISSION_GRANTED)
                ) {
                    checkSettings()
                } else {
                    Toast.makeText(requireActivity(), "Location permission denied.",
                        Toast.LENGTH_SHORT).show()
                    Navigation.findNavController(requireView())
                        .navigate(R.id.action_fillWifiDetailFragment_to_connectDeviceFragment)
//                    val action =
//                        FillWifiDetailFragmentDirections.actionFillWifiDetailFragmentToConnectDeviceFragment()
//                    findNavController().navigate(action)
                }
                return
            }

            // Add other 'when' lines to check for other
            // permissions this app might request.
            else -> {
                // Ignore all other requests.
            }
        }
    }

    private fun showWifiPasswordDialog() {
//        if (!btSocket.isConnected) connectToBtDevice()
        val builder = AlertDialog.Builder(requireActivity())

        val view = requireActivity().layoutInflater.inflate(R.layout.wifi_dialog_layout, null)

        val passwordET = view.findViewById<TextView>(R.id.wifi_password_et)

        builder.setView(view)
            .setTitle(ssid)
            .setNeutralButton("Cancel") { _, _ -> }
            .setPositiveButton("Submit") { _, _ ->
                val passwod = passwordET.text.toString()
                if (passwod.isBlank()) {
                    Toast.makeText(requireActivity(), "Please enter a password", Toast.LENGTH_SHORT)
                        .show()
                } else sendPasswordToDevice(passwod)
            }

        builder.create()
        builder.show()
    }

    private fun sendSSIDToDevice() {
        // TODO: If anything goes wrong try to remove space at the end
        val wifiSSID = "SSID:$ssid "

        Log.d(TAG, "sendSSIDToDevice: $wifiSSID")
        try {
            val ssidOutputStream: OutputStream = btSocket!!.outputStream
            ssidOutputStream.write(wifiSSID.toByteArray())

//            closeSocket()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sendPasswordToDevice(password: String) {
        loadingDialog.show(childFragmentManager, TAG)
        // TODO: If anything goes wrong try to remove space at the end
        val wifiPassword = "PASS:$password "
        val spEditor = sharedPref?.edit()

        Log.d(TAG, "sendPasswordToDevice: $wifiPassword")
        // TODO: Show a waiting dialog. In that check if wifi is online on the SL device
        try {
            val passwordOutputStream: OutputStream = btSocket!!.outputStream
            passwordOutputStream.write(wifiPassword.toByteArray())

            // TODO: Check Wifi
            addBluetoothId(deviceId, btDevice)

            spEditor?.putString(deviceId, btDevice)

            deviceIdsSet.add(deviceId)
            spEditor?.putStringSet(DEVICEIDSSET, deviceIdsSet)
            spEditor?.apply()

            loadingDialog.dismiss()
            closeSocket()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun addBluetoothId(deviceId: String, bluetoothId: String) {
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val addBluetoothUrl = getString(R.string.base_url) + getString(R.string.url_add_bleutooth)

        val switchListRequest = object : StringRequest(Method.POST, addBluetoothUrl,
            { response ->
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        Log.d(TAG, "addBluetoothId: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
                        Log.e(TAG, "addBluetoothId: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    Log.e(TAG, "Exception in addBluetoothId: $e")
                    showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["device_id"] = deviceId
                params["bluetooth"] = bluetoothId
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }

        requestQueue.add(switchListRequest)
    }

    private fun closeSocket() {
        try {
            btSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "connectToBtDevice: Socket Close Error : ", e)
        }
    }

    private fun connectToBtDevice() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        val remoteDevice = btAdapter.getRemoteDevice(btDevice)

        Toast.makeText(requireActivity(), "Device Name - ${remoteDevice.name}", Toast.LENGTH_SHORT)
            .show()

        btSocket = remoteDevice.createRfcommSocketToServiceRecord(mUUID)
        try {
            Log.i(TAG, "connectToBtDevice: Trying to connect socket.")
            btSocket!!.connect()
            sendSSIDToDevice()
            showWifiPasswordDialog()
            Log.d(TAG, "connectToBtDevice: Connected = ${btSocket?.isConnected}")
            loadingDialog.dismiss()
        } catch (e: Exception) {
            Snackbar.make(bind.fwRootView,
                "Timeout! Make sure you are close to the ${getString(R.string.app_name)} device.",
                Snackbar.LENGTH_LONG).setAnchorView(bind.refreshFab).show()
            Log.e(TAG, "connectToBtDevice: Socket Connect Error : ", e)
            Log.d(TAG, "connectToBtDevice: Connected = ${btSocket?.isConnected}")
            loadingDialog.dismiss()
            closeSocket()
        }

        if (btSocket?.isConnected == true) {
            getWifiDetails()
        } else snackbar?.show()
    }

    private fun getWifiDetails() {
        Log.d(TAG, "getWifiDetails: Socket is connected.")
        bind.refreshFab.visibility = View.GONE
        Handler(Looper.getMainLooper()).postDelayed({
            bind.refreshFab.visibility = View.VISIBLE
        }, 10000)

        wifiManager = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wifiScanReceiver = object : BroadcastReceiver() {

            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    scanSuccess(wifiManager!!)
                } else {
                    scanFailure(wifiManager!!)
                }
            }
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        requireContext().registerReceiver(wifiScanReceiver, intentFilter)

        val success = wifiManager!!.startScan()
        if (!success) {
            // scan failure handling
            scanFailure(wifiManager!!)
        }
    }

    private fun scanSuccess(wifiManager: WifiManager) {
        val results = wifiManager.scanResults
        Log.d(TAG, "scanSuccess: $results")
        wifiSSIDList.clear()

        results.forEach {
            Log.d(TAG, "scanSuccess: ${it.SSID}")
            wifiSSIDList.add(it.SSID)
            listAdapter.notifyDataSetChanged()
        }
        bind.wifiLv.adapter = listAdapter
    }

    private fun scanFailure(wifiManager: WifiManager) {
        // handle failure: new scan did NOT succeed
        // consider using old scan results: these are the OLD results!
        val results = wifiManager.scanResults
        Log.d(TAG, "scanFailure: $results")
        wifiSSIDList.clear()

        results.forEach {
            Log.d(TAG, "scanFailure: ${it.SSID}")
            wifiSSIDList.add(it.SSID)
            listAdapter.notifyDataSetChanged()
        }

        bind.wifiLv.adapter = listAdapter
        // TODO: May remove if something wrong happens, added extra
        getWifiDetails()
    }

    private fun showTurnOnGPSDialog() {
        val builder = AlertDialog.Builder(requireActivity())
        builder.setMessage("Please turn on your GPS before moving forward.")
            .setNeutralButton("Cancel") { _, _ ->
                val action =
                    FillWifiDetailFragmentDirections.actionFillWifiDetailFragmentToRoomsFragment()
                findNavController().navigate(action)
            }
            .setPositiveButton("Ok") { _, _ ->
                checkSettings()
            }

        builder.create()
        builder.show()
    }

    private fun showTurnOnWifiDialog() {
        val builder = AlertDialog.Builder(requireActivity())
        builder.setMessage("Please turn on your Wifi before moving forward.")
            .setNeutralButton("Cancel") { _, _ ->
                val action =
                    FillWifiDetailFragmentDirections.actionFillWifiDetailFragmentToRoomsFragment()
                findNavController().navigate(action)
            }
            .setPositiveButton("Ok") { _, _ ->
                checkSettings()
            }

        builder.create()
        builder.show()
    }

    private fun showToast(message: String?) {
        Toast.makeText(requireActivity(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /*private fun retryDialog() {
        val builder = AlertDialog.Builder(requireActivity())
        builder.setTitle("Failed to connect")
            .setMessage("There was a problem connecting to the device. Please try again.")
            .setPositiveButton(
                "Try again"
            ) { _, _ ->
                connectToBtDevice()
            }
            .setNeutralButton("Go back") { _, _ ->
                dialog.cancel()
                dialog.dismiss()
                val action =
                    FillWifiDetailFragmentDirections.actionFillWifiDetailFragmentToRoomsFragment()
                findNavController().navigate(action)
            }
        // Create the AlertDialog object and return it
        dialog = builder.create()
        dialog.show()
    }*/

    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 102
    }
}