package com.kfilesync.mobile.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point exposed to Swift.
 *
 * Wraps the shared Compose `App()` in a UIViewController so the Swift host
 * (iosApp/iOSApp.swift) can present it via UIViewControllerRepresentable.
 *
 * Lives in sharedUI/iosMain because both the Compose `App()` it wraps and the
 * exported framework (`SharedUI`) originate from this module.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }