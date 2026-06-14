package dev.jdtech.jellyfin.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.widget.FrameLayout

internal class FindroidCarBrowsePresentation(
    context: Context,
    display: Display,
    private val surface: FindroidCarSurface,
) : Presentation(context, display) {
    private lateinit var browseView: FindroidCarBrowseView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        browseView = FindroidCarBrowseView(context, surface)
        val container =
            FrameLayout(context).apply {
                setBackgroundColor(FindroidCarSurfaceColors.BG)
                addView(
                    browseView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        setContentView(container)
    }

    fun setState(state: FindroidCarBrowseState) {
        if (::browseView.isInitialized) browseView.setState(state)
    }

    fun currentState(): FindroidCarBrowseState =
        if (::browseView.isInitialized) browseView.currentState() else FindroidCarBrowseState()

    fun scrollBy(dy: Float) {
        if (::browseView.isInitialized) browseView.scrollBy(dy)
    }

    fun hitTest(x: Float, y: Float): HitRegion.Payload? =
        if (::browseView.isInitialized) browseView.hitTest(x, y) else null
}
