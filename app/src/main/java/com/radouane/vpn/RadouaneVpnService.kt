package com.radouane.vpn

import android.net.VpnService

/**
 * VPN service declaration and permission entry point.
 * The Xray/libv2ray engine and TUN-to-proxy bridge must be integrated before
 * this service can establish a working V2Ray tunnel. It intentionally does
 * not create a fake connected state or capture traffic without an engine.
 */
class RadouaneVpnService : VpnService()
