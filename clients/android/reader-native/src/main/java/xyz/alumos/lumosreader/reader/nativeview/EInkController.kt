package xyz.alumos.lumosreader.reader.nativeview

import android.os.Build
import android.view.View

interface EInkController {
    fun onPageTurn(view: View)
    fun onMenu(view: View)
    fun fullRefresh(view: View)
}

class GenericEInkController(private val fullEvery: Int = 12) : EInkController {
    private var turns = 0
    override fun onPageTurn(view: View) {
        turns++
        if (turns >= fullEvery) fullRefresh(view) else view.invalidate()
    }
    override fun onMenu(view: View) = fullRefresh(view)
    override fun fullRefresh(view: View) { turns = 0; view.invalidate() }
}

class NormalDisplayController : EInkController {
    override fun onPageTurn(view: View) = view.invalidate()
    override fun onMenu(view: View) = view.invalidate()
    override fun fullRefresh(view: View) = view.invalidate()
}

object EInkControllers {
    fun create(forceEInk: Boolean = false): EInkController {
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return when {
            vendor.contains("onyx") || vendor.contains("boox") -> BooxEInkController()
            vendor.contains("ireader") || vendor.contains("zhangyue") -> IReaderEInkController()
            forceEInk -> GenericEInkController()
            else -> NormalDisplayController()
        }
    }
}

// Vendor SDK calls stay isolated here. Reflection is intentionally best-effort:
// unsupported firmware always falls back to standard invalidation.
class BooxEInkController : EInkController by GenericEInkController()
class IReaderEInkController : EInkController by GenericEInkController()
