package com.mobilerun.portal.api

import com.mobilerun.portal.location.LocationReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceReadingsTest {
    @Test
    fun timeZone_isTheShapeDevicesApiDecodes() {
        val json = JSONObject(timeZoneResponse("Asia/Baku").toJson(7))

        assertEquals("success", json.getString("status"))
        assertEquals("Asia/Baku", json.getJSONObject("result").getString("timezone"))
    }

    @Test
    fun location_fixIsTheShapeDevicesApiDecodes() {
        val result = JSONObject(locationResponse(LocationReading.Fix(40.4093, 49.8671)).toJson(7))
            .getJSONObject("result")

        assertEquals(40.4093, result.getDouble("latitude"), 0.0)
        assertEquals(49.8671, result.getDouble("longitude"), 0.0)
    }

    @Test
    fun location_unavailableIsAnError() {
        val json = JSONObject(locationResponse(LocationReading.Unavailable("off")).toJson(7))

        assertEquals("error", json.getString("status"))
        assertEquals("off", json.getString("error"))
    }
}
