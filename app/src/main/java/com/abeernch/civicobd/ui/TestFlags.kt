package com.abeernch.civicobd.ui

/**
 * Switches for the emulator test build only (set from launch extras when TEST_HOOKS is on), used to find
 * which part of the Drive tab an emulator cannot draw. Always off on the phone.
 */
object TestFlags {
    var noIntro = false
    var rectClip = false
    var noPage = false
    var classicFont = false
    var noCluster = false
}
