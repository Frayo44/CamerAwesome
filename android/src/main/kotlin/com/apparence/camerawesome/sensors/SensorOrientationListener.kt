package com.apparence.camerawesome.sensors

import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink

class SensorOrientationListener : EventChannel.StreamHandler, SensorOrientation {
    var events: EventSink? = null
    override fun onListen(arguments: Any, events: EventSink) {
        this.events = events
    }

    override fun onCancel(arguments: Any?) {
        // No endOfStream(): Dart caches one broadcast stream for orientation,
        // and ending it when the last listener leaves (e.g. every rotating
        // button rebuilt at once) closed it for good - later buttons never
        // rotated again. Dropping the sink lets the next listen re-attach.
        events = null
    }

    override fun onOrientationChanged(orientation: Int) {
        if (events == null) {
            return
        }
        when (orientation) {
            0 -> events!!.success("PORTRAIT_UP")
            90 -> events!!.success("LANDSCAPE_LEFT")
            180 -> events!!.success("PORTRAIT_DOWN")
            270 -> events!!.success("LANDSCAPE_RIGHT")
        }
    }
}