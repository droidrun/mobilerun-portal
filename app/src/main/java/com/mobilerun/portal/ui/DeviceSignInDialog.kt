package com.mobilerun.portal.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.mobilerun.portal.R
import com.mobilerun.portal.auth.DeviceCodeAuthClient
import com.mobilerun.portal.auth.DeviceSignInController
import com.mobilerun.portal.auth.DeviceSignInState
import com.mobilerun.portal.auth.MainScheduler
import com.mobilerun.portal.config.ConfigManager
import java.util.concurrent.Executors

/** Device-code sign-in: shows the code and a QR of the approval link. */
class DeviceSignInDialog(
    private val activity: AppCompatActivity,
    private val onAuthorized: (String) -> Unit,
) {
    private companion object {
        const val QR_SIZE_PX = 512
    }

    private val handler = Handler(Looper.getMainLooper())
    private val controller = DeviceSignInController(
        client = DeviceCodeAuthClient(),
        executor = Executors.newSingleThreadExecutor(),
        main = object : MainScheduler {
            override fun postDelayed(task: Runnable, delayMs: Long) {
                handler.postDelayed(task, delayMs)
            }

            override fun remove(task: Runnable) = handler.removeCallbacks(task)
        },
        elapsedRealtime = SystemClock::elapsedRealtime,
        onState = ::render,
        onAuthorized = { token ->
            dialog?.dismiss()
            onAuthorized(token)
        },
    )

    private var dialog: AlertDialog? = null
    private lateinit var qr: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var code: TextView
    private lateinit var address: TextView
    private lateinit var status: TextView
    private lateinit var primary: MaterialButton

    fun show() {
        if (dialog?.isShowing == true) return
        val view = activity.layoutInflater.inflate(R.layout.dialog_device_sign_in, null)
        qr = view.findViewById(R.id.image_sign_in_qr)
        progress = view.findViewById(R.id.progress_sign_in)
        code = view.findViewById(R.id.text_sign_in_code)
        address = view.findViewById(R.id.text_sign_in_address)
        status = view.findViewById(R.id.text_sign_in_status)
        primary = view.findViewById(R.id.btn_sign_in_primary)
        val created = AlertDialog.Builder(activity, R.style.Theme_MobilerunPortal_Dialog)
            .setView(view)
            .setOnDismissListener { controller.cancel() }
            .create()
        created.setCanceledOnTouchOutside(false)
        created.window?.setBackgroundDrawableResource(R.color.background_card)
        view.findViewById<MaterialButton>(R.id.btn_sign_in_cancel).setOnClickListener {
            created.dismiss()
        }
        primary.setOnClickListener { onPrimary() }
        dialog = created
        created.show()
        created.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
        startNewCode()
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun dismiss() {
        controller.takePendingToken()?.let { DeviceCodeAuthClient().signOut(it) }
        dialog?.dismiss()
        dialog = null
    }

    private fun startNewCode() {
        controller.start(ConfigManager.getInstance(activity).deviceID)
    }

    private fun onPrimary() {
        val waiting = controller.state as? DeviceSignInState.Waiting
        if (waiting == null) {
            startNewCode()
            return
        }
        val authorization = waiting.authorization
        val url = authorization.verificationUriComplete
            .takeIf(DeviceCodeAuthClient::isSafeVerificationUri)
            ?: authorization.verificationUri
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.could_not_open_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(state: DeviceSignInState) {
        if (dialog?.isShowing != true) return
        val waiting = state as? DeviceSignInState.Waiting
        progress.visibility = if (state == DeviceSignInState.Requesting) View.VISIBLE else View.GONE
        if (waiting != null) {
            val authorization = waiting.authorization
            code.text = DeviceCodeAuthClient.formatUserCode(authorization.userCode)
            address.text = authorization.verificationUri.removePrefix("https://")
            val bitmap = authorization.verificationUriComplete
                .takeIf(DeviceCodeAuthClient::isSafeVerificationUri)
                ?.let(::qrBitmap)
            qr.setImageBitmap(bitmap)
            qr.visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
        } else {
            code.text = ""
            address.text = ""
            qr.visibility = View.INVISIBLE
        }
        status.text = when (state) {
            DeviceSignInState.Idle, DeviceSignInState.Requesting ->
                activity.getString(R.string.device_sign_in_requesting)

            is DeviceSignInState.Waiting -> activity.getString(R.string.device_sign_in_waiting)
            DeviceSignInState.Expired -> activity.getString(R.string.device_sign_in_expired)
            DeviceSignInState.Denied -> activity.getString(R.string.device_sign_in_denied)
            DeviceSignInState.Offline -> activity.getString(R.string.device_sign_in_offline)
            is DeviceSignInState.Failed -> activity.getString(R.string.device_sign_in_failed, state.code)
        }
        primary.isEnabled = state != DeviceSignInState.Requesting && state != DeviceSignInState.Idle
        primary.setText(
            if (waiting != null || state == DeviceSignInState.Requesting) {
                R.string.device_sign_in_open_here
            } else {
                R.string.device_sign_in_new_code
            },
        )
    }

    private fun qrBitmap(content: String): Bitmap? = try {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX) { index ->
            if (matrix[index % QR_SIZE_PX, index / QR_SIZE_PX]) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.ARGB_8888)
    } catch (_: WriterException) {
        null
    }
}
