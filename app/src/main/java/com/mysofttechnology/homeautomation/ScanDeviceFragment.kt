package com.mysofttechnology.homeautomation

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.android.volley.toolbox.StringRequest
import com.budiyev.android.codescanner.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mysofttechnology.homeautomation.StartActivity.Companion.BLANK
import com.mysofttechnology.homeautomation.StartActivity.Companion.FRI
import com.mysofttechnology.homeautomation.StartActivity.Companion.MON
import com.mysofttechnology.homeautomation.StartActivity.Companion.SAT
import com.mysofttechnology.homeautomation.StartActivity.Companion.START_TIME
import com.mysofttechnology.homeautomation.StartActivity.Companion.STOP_TIME
import com.mysofttechnology.homeautomation.StartActivity.Companion.SUN
import com.mysofttechnology.homeautomation.StartActivity.Companion.THU
import com.mysofttechnology.homeautomation.StartActivity.Companion.TUE
import com.mysofttechnology.homeautomation.StartActivity.Companion.WED
import com.mysofttechnology.homeautomation.StartActivity.Companion.ZERO
import com.mysofttechnology.homeautomation.databinding.FragmentScanDeviceBinding
import com.mysofttechnology.homeautomation.utils.VolleySingleton
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ScanDeviceFragment"

class ScanDeviceFragment : Fragment() {

    private lateinit var deviceId: String
    private var currentUserId: String? = null
    private var sharedPref: SharedPreferences? = null
    private var updateDialog: AlertDialog? = null

    private var _binding: FragmentScanDeviceBinding? = null
    private val binding get() = _binding!!

    private var snackbar: Snackbar? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var codeScanner: CodeScanner
    private lateinit var loadingDialog: LoadingDialog

    private val mRegexPattern = "^Smartlit[15]_\\d{5,}".toRegex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadingDialog = LoadingDialog()
        loadingDialog.isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentScanDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE) ?: return
        currentUserId = sharedPref!!.getString(getString(R.string.current_user_id), "")

        snackbar =
            Snackbar.make(binding.sdRootView, "Permissions are not granted", Snackbar.LENGTH_SHORT)
                .setAction("SETTINGS") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID)))
                }
                .setAnchorView(binding.sdContinueBtn)


        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all {
                it.value == true
            }

            if (granted) {
                val action =
                    ScanDeviceFragmentDirections.actionScanDeviceFragmentToConnectDeviceFragment(
                        deviceId)
                if (findNavController().currentDestination?.id == R.id.scanDeviceFragment)
                    Navigation.findNavController(requireView()).navigate(action)
            } else snackbar?.show()
        }

        checkCameraPermission()
        codeScanner()

        binding.sdAllowBtn.setOnClickListener {
            checkCameraPermission()
        }

        binding.backBtn.setOnClickListener {
            binding.backBtn.isEnabled = false
            Navigation.findNavController(it)
                .navigate(R.id.action_scanDeviceFragment_to_roomsFragment)
        }

        binding.sdContinueBtn.setOnClickListener {
            binding.sdContinueBtn.isEnabled = false
            deviceId = binding.deviceIdEt.text.toString()
            if (deviceId.isNotEmpty() && mRegexPattern.containsMatchIn(deviceId)
            ) {
                if (!loadingDialog.isAdded) loadingDialog.show(childFragmentManager, TAG)
                checkDeviceAvailability(deviceId)
            } else {
                Toast.makeText(
                    requireActivity(),
                    "Enter a valid device id",
                    Toast.LENGTH_SHORT
                ).show()
                binding.sdContinueBtn.isEnabled = true
            }
        }
    }

    private fun checkCameraPermission() {
        activityResultLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun codeScanner() {
        codeScanner = CodeScanner(requireActivity(), binding.barcodeScanner)

        codeScanner.apply {
            camera = CodeScanner.CAMERA_BACK
            formats = CodeScanner.ALL_FORMATS

            autoFocusMode = AutoFocusMode.SAFE
            scanMode = ScanMode.SINGLE
            isAutoFocusEnabled = false
            isFlashEnabled = false

            // RESPONSE if worked
            decodeCallback = DecodeCallback {
                requireActivity().runOnUiThread {
                    if (mRegexPattern.containsMatchIn(
                            it.toString())
                    ) {
                        binding.deviceIdEt.setText(it.toString())
                        if (!loadingDialog.isAdded) loadingDialog.show(childFragmentManager, TAG)
                        deviceId = it.toString()
                        checkDeviceAvailability(deviceId)
                    } else {
                        showToast("Device not identified")
                        codeScanner.startPreview()
                    }
                }
            }

            errorCallback = ErrorCallback {
                requireActivity().runOnUiThread {
                    Log.d(TAG, "Camera Initialisation Error: ${it.message}")
                }
            }

            binding.barcodeScanner.setOnClickListener {
                codeScanner.startPreview()
            }
        }
    }

    private fun checkDeviceAvailability(deviceId: String) {
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val url = getString(R.string.base_url) + getString(R.string.url_room_list)

        val stringRequest = object : StringRequest(Method.POST, url,
            { response ->
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        val roomListData = mData.get("data") as JSONArray
                        if (roomExists(roomListData, deviceId)) {
                            showToast("Device already exists")
                            gotoConnectDevice()
                        } else {
//                            showToast("New device")
                            checkChild(deviceId)
                        }
                        Log.d(TAG, "checkDeviceAvailability: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
//                        showToast("New device")
                        checkChild(deviceId)
                        Log.d(TAG, "checkDeviceAvailability: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    binding.sdContinueBtn.isEnabled = true
                    Log.e(TAG, "Exception in checkDeviceAvailability: $e")
                    if (e.message != null) showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                binding.sdContinueBtn.isEnabled = true
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["user_id"] = currentUserId.toString()
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }
        requestQueue.add(stringRequest)
    }

    private fun checkChild(deviceId: String) {
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val url = getString(R.string.base_url) + getString(R.string.url_check_child)

        val stringRequest = object : StringRequest(Method.POST, url,
            { response ->
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        val parentNum = mData.get("otpmobilenum").toString()
                        if (parentNum.length == 10 && parentNum.isDigitsOnly()) {
                            loadingDialog.dismiss()
                            binding.sdContinueBtn.isEnabled = true
                            showOtpVerificationDialog(deviceId)
                        } else {
//                            showToast("New User")
                            addDevice(deviceId)
                        }
                        Log.d(TAG, "checkChild: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
//                        showToast("New User")
                        addDevice(deviceId)
                        Log.d(TAG, "checkChild: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    binding.sdContinueBtn.isEnabled = true
                    Log.e(TAG, "Exception in checkChild: $e")
                    if (e.message != null) showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                binding.sdContinueBtn.isEnabled = true
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["device_id"] = deviceId
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }
        requestQueue.add(stringRequest)
    }

    private fun showOtpVerificationDialog(deviceId: String) {
        val builder = AlertDialog.Builder(requireActivity())
        builder
            .setTitle("Registered Device")
            .setMessage("This device is already registered. You need to verify to use it.")
            .setPositiveButton("Ok"
            ) { _, _ ->
                showVerifyOtpDialog(deviceId)
                binding.sdContinueBtn.isEnabled = true
            }
            .setNegativeButton("Cancel") { _, _ -> }
        builder.create()
        builder.show()
    }

    private fun showVerifyOtpDialog(deviceId: String) {
        val builder = AlertDialog.Builder(requireActivity())

        val view = requireActivity().layoutInflater.inflate(R.layout.verify_otp_layout, null)

        val otpET = view.findViewById<TextView>(R.id.verify_otp_et)
        val submitBtn = view.findViewById<TextView>(R.id.vo_submit_btn)
        val howToBtn = view.findViewById<TextView>(R.id.how_to_tv_btn)
        val howToDesc = view.findViewById<TextView>(R.id.how_to_desc_tv)

        builder.setView(view).setTitle("Verify OTP")

        howToBtn.setOnClickListener {
            howToDesc.visibility = View.VISIBLE
        }

        submitBtn.setOnClickListener {
            val code = otpET.text.toString()

            if (code.isNotEmpty() && code.length == 6 && code.isDigitsOnly()) {
                verifyOtpCode(code, deviceId)
            } else {
                otpET.error = "Enter a proper 6-digit code"
            }
        }

        updateDialog = builder.create()
        updateDialog?.show()
    }

    private fun verifyOtpCode(code: String, deviceId: String) {
        Log.d(TAG, "verifyOtpCode: deviceId - $deviceId")
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val getOtpUrl = getString(R.string.base_url) + getString(R.string.url_get_otp)

        if (!loadingDialog.isAdded) loadingDialog.show(childFragmentManager, TAG)

        val getOtpRequest = object : StringRequest(Method.POST, getOtpUrl,
            { response ->
                Log.i(TAG, "verifyOtpCode: $response")
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        loadingDialog.dismiss()
                        val otp = mData.getString("otp")
                        if (otp.length == 6 && otp.isDigitsOnly()) {
                            if (otp == code) {
                                updateDialog?.dismiss()
                                addDevice(deviceId)
                                showToast("OTP verified.")
                            } else {
                                showToast("Wrong OTP")
                            }
                        } else {
                            showToast("Please generate a otp first.")
                        }

                        Log.d(TAG, "verifyOtpCode: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
                        showToast("Please generate a otp first.")
                        Log.e(TAG, "verifyOtpCode: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    Log.e(TAG, "Exception in verifyOtpCode: $e")
                    if (e.message != null) showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["device_id"] = deviceId
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }
        requestQueue.add(getOtpRequest)
    }

    private fun roomExists(roomListData: JSONArray, deviceId: String): Boolean {
        var flag = false
        for (i in 0 until roomListData.length()) {
            val room = roomListData.getJSONObject(i)
            if (room.get("device_id") == deviceId) {
                flag = true
            }
        }
        return flag
    }

    private fun addDevice(deviceId: String) {
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val url = getString(R.string.base_url) + getString(R.string.url_room)

        val stringRequest = object : StringRequest(Method.POST, url,
            { response ->
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        if (deviceId.elementAt(2).toString() == "1") createSwitch(deviceId, 6)
                        else {
                            for (i in 1..5) createSwitch(deviceId, i)
                        }
                        Log.d(TAG, "addDevice: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
                        binding.sdContinueBtn.isEnabled = true
                        showToast("unable to create room")
                        Log.e(TAG, "addDevice: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    binding.sdContinueBtn.isEnabled = true
                    Log.e(TAG, "Exception in addDevice: $e")
                    if (e.message != null) showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                binding.sdContinueBtn.isEnabled = true
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["device_id"] = deviceId
                params["user_id"] = currentUserId.toString()
                params["room_name"] = "Room $deviceId"
                Log.i(TAG, "getParams: $deviceId | ${deviceId.elementAt(2)}")
                params["switch_count"] = deviceId.elementAt(2).toString()
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }
        requestQueue.add(stringRequest)
    }

    private fun createSwitch(deviceId: String, i: Int) {
        val requestQueue = VolleySingleton.getInstance(requireContext()).requestQueue
        val url = getString(R.string.base_url) + getString(R.string.url_switch)

        val stringRequest = object : StringRequest(Method.POST, url,
            { response ->
                try {
                    val mData = JSONObject(response.toString())
                    val resp = mData.get("response") as Int
                    val msg = mData.get("msg")

                    if (resp == 1) {
                        if (i >= 5) gotoConnectDevice()
                        Log.d(TAG, "createSwitch: Message - $msg")
                    } else {
                        loadingDialog.dismiss()
                        binding.sdContinueBtn.isEnabled = true
                        showToast("Failed to create all data")
                        Log.e(TAG, "createSwitch: Message - $msg")
                    }
                } catch (e: Exception) {
                    loadingDialog.dismiss()
                    binding.sdContinueBtn.isEnabled = true
                    Log.e(TAG, "Exception in createSwitch: $e")
                    if (e.message != null) showToast(e.message)
                }
            }, {
                loadingDialog.dismiss()
                binding.sdContinueBtn.isEnabled = true
                showToast("Something went wrong.")
                Log.e(TAG, "VollyError: ${it.message}")
            }) {
            override fun getParams(): Map<String, String> {
                val params = HashMap<String, String>()
                params["device_id"] = deviceId
                params["mobile_no"] = currentUserId.toString()
                params["switch"] = if (i == 5) "Fan" else if (i == 6) "Switch" else "Switch $i"
                params["icon"] = ZERO
                params[START_TIME] = BLANK
                params[STOP_TIME] = BLANK
                params[SUN] = ZERO
                params[MON] = ZERO
                params[TUE] = ZERO
                params[WED] = ZERO
                params[THU] = ZERO
                params[FRI] = ZERO
                params[SAT] = ZERO
                params["switch_id_by_app"] = i.toString()
                return params
            }

            override fun getHeaders(): MutableMap<String, String> {
                val params = HashMap<String, String>()
                params["Content-Type"] = "application/x-www-form-urlencoded"
                return params
            }
        }
        requestQueue.add(stringRequest)
    }

    private fun gotoConnectDevice() {
        binding.sdContinueBtn.isEnabled = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Is device online?")
            .setMessage(
                "You can Skip if  the device is online but if this is your first time setting up the device you must add wifi detail. Do you want to add wifi details?")
            .setCancelable(false)
            .setNegativeButton("Skip") { _, _ ->
                val action =
                    ScanDeviceFragmentDirections.actionScanDeviceFragmentToDashbordFragment()
                if (findNavController().currentDestination?.id == R.id.scanDeviceFragment)
                    findNavController().navigate(action)
                loadingDialog.dismiss()
            }
            .setPositiveButton("Ok") { _, _ ->
//                val action =
//                    ScanDeviceFragmentDirections.actionScanDeviceFragmentToConnectDeviceFragment(deviceId)
//                if (findNavController().currentDestination?.id == R.id.scanDeviceFragment)
//                    findNavController().navigate(action)
                loadingDialog.dismiss()
                checkAllPermissions()
            }
//            .setNeutralButton("Cancel") { _, _ -> }
            .show()

    }

    private fun checkAllPermissions() {
        val permReqList: MutableList<String> = arrayListOf()

        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        )
            permReqList.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        )
            permReqList.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            )
                permReqList.add(Manifest.permission.BLUETOOTH_SCAN)

            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            )
                permReqList.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (permReqList.isNotEmpty()) {
            permissionLauncher.launch(permReqList.toTypedArray())
        } else {
            val action =
                ScanDeviceFragmentDirections.actionScanDeviceFragmentToConnectDeviceFragment(
                    deviceId)
            if (findNavController().currentDestination?.id == R.id.scanDeviceFragment)
                Navigation.findNavController(requireView()).navigate(action)
        }
    }

    private val activityResultLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Handle Permission granted/rejected
        if (isGranted) {
            binding.barcodeScanner.visibility = View.VISIBLE
            binding.permNotAllowedLayout.visibility = View.GONE
        } else {
//            Toast.makeText(requireActivity(), "Camera permission not granted!", Toast.LENGTH_SHORT)
            snackbar?.show()
            binding.permNotAllowedLayout.visibility = View.VISIBLE
        }
    }

    private fun showToast(message: String?) {
        Toast.makeText(requireActivity(), message, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()

        codeScanner.startPreview()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        snackbar?.dismiss()
    }
}