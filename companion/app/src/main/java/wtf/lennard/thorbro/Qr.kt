package wtf.lennard.thorbro

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** QR codes for passing ThorBro on: the download address, or the Wi-Fi Direct network (phones join it by scanning) */
object Qr {
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))

    /** black on white, one unit per module */
    fun svg(text: String): String {
        val m = matrix(text)
        val path = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) path.append("M$x ${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    /** standard Wi-Fi QR: the Android/iOS camera offers to join the network */
    fun wifi(ssid: String, pass: String): String {
        fun e(s: String) = s.replace(Regex("""([\;,:"])"""), """\\$1""")
        return "WIFI:T:WPA;S:${e(ssid)};P:${e(pass)};;"
    }
}
