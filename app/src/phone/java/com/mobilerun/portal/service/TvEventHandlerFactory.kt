package com.mobilerun.portal.service

internal object TvEventHandlerFactory {
    fun create(
        service: MobilerunAccessibilityService,
        updateUi: (String, String?) -> Unit,
        onAutoAcceptFailed: () -> Unit,
    ): MobilerunAccessibilityService.TvEventHandler? = null
}
