package com.mobilerun.portal.api

import com.mobilerun.portal.location.LocationReading
import org.json.JSONObject

/** `timezone/get` result, in the shape devices-api decodes. */
internal fun timeZoneResponse(id: String): ApiResponse =
    ApiResponse.RawObject(JSONObject().put("timezone", id))

/** `location/get` result, in the shape devices-api decodes. */
internal fun locationResponse(reading: LocationReading): ApiResponse = when (reading) {
    is LocationReading.Fix -> ApiResponse.RawObject(
        JSONObject().put("latitude", reading.latitude).put("longitude", reading.longitude),
    )
    is LocationReading.Unavailable -> ApiResponse.Error(reading.message)
}
