/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity.tethering;

import static android.provider.Settings.Global.TETHER_OFFLOAD_DISABLED;

import android.content.ContentResolver;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.util.SharedLog;
import android.os.Handler;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A class to encapsulate the business logic of programming the tethering
 * hardware offload interface.
 *
 * @hide
 */
public class OffloadController {
    private static final String TAG = OffloadController.class.getSimpleName();

    private final Handler mHandler;
    private final OffloadHardwareInterface mHwInterface;
    private final ContentResolver mContentResolver;
    private final SharedLog mLog;
    private boolean mConfigInitialized;
    private boolean mControlInitialized;
    private LinkProperties mUpstreamLinkProperties;
    // The complete set of offload-exempt prefixes passed in via Tethering from
    // all upstream and downstream sources.
    private Set<IpPrefix> mExemptPrefixes;
    // A strictly "smaller" set of prefixes, wherein offload-approved prefixes
    // (e.g. downstream on-link prefixes) have been removed and replaced with
    // prefixes representing only the locally-assigned IP addresses.
    private Set<String> mLastLocalPrefixStrs;

    public OffloadController(Handler h, OffloadHardwareInterface hwi,
            ContentResolver contentResolver, SharedLog log) {
        mHandler = h;
        mHwInterface = hwi;
        mContentResolver = contentResolver;
        mLog = log.forSubComponent(TAG);
        mExemptPrefixes = new HashSet<>();
        mLastLocalPrefixStrs = new HashSet<>();
    }

    public void start() {
        if (started()) return;

        if (isOffloadDisabled()) {
            mLog.i("tethering offload disabled");
            return;
        }

        if (!mConfigInitialized) {
            mConfigInitialized = mHwInterface.initOffloadConfig();
            if (!mConfigInitialized) {
                mLog.i("tethering offload config not supported");
                stop();
                return;
            }
        }

        mControlInitialized = mHwInterface.initOffloadControl(
                new OffloadHardwareInterface.ControlCallback() {
                    @Override
                    public void onStarted() {
                        mLog.log("onStarted");
                    }

                    @Override
                    public void onStoppedError() {
                        mLog.log("onStoppedError");
                    }

                    @Override
                    public void onStoppedUnsupported() {
                        mLog.log("onStoppedUnsupported");
                    }

                    @Override
                    public void onSupportAvailable() {
                        mLog.log("onSupportAvailable");

                        // [1] Poll for statistics and notify NetworkStats
                        // [2] (Re)Push all state:
                        //     [a] push local prefixes
                        //     [b] push downstreams
                        //     [c] push upstream parameters
                        pushUpstreamParameters();
                    }

                    @Override
                    public void onStoppedLimitReached() {
                        mLog.log("onStoppedLimitReached");
                        // Poll for statistics and notify NetworkStats
                    }

                    @Override
                    public void onNatTimeoutUpdate(int proto,
                                                   String srcAddr, int srcPort,
                                                   String dstAddr, int dstPort) {
                        mLog.log(String.format("NAT timeout update: %s (%s,%s) -> (%s,%s)",
                                proto, srcAddr, srcPort, dstAddr, dstPort));
                    }
                });
        if (!mControlInitialized) {
            mLog.i("tethering offload control not supported");
            stop();
        }
        mLog.log("tethering offload started");
    }

    public void stop() {
        final boolean wasStarted = started();
        mUpstreamLinkProperties = null;
        mHwInterface.stopOffloadControl();
        mControlInitialized = false;
        mConfigInitialized = false;
        if (wasStarted) mLog.log("tethering offload stopped");
    }

    public void setUpstreamLinkProperties(LinkProperties lp) {
        if (!started() || Objects.equals(mUpstreamLinkProperties, lp)) return;

        mUpstreamLinkProperties = (lp != null) ? new LinkProperties(lp) : null;
        // TODO: examine return code and decide what to do if programming
        // upstream parameters fails (probably just wait for a subsequent
        // onOffloadEvent() callback to tell us offload is available again and
        // then reapply all state).
        computeAndPushLocalPrefixes();
        pushUpstreamParameters();
    }

    public void setLocalPrefixes(Set<IpPrefix> localPrefixes) {
        if (!started()) return;

        mExemptPrefixes = localPrefixes;
        computeAndPushLocalPrefixes();
    }

    public void notifyDownstreamLinkProperties(LinkProperties lp) {
        if (!started()) return;

        // TODO: Cache LinkProperties on a per-ifname basis and compute the
        // deltas, calling addDownstream()/removeDownstream() accordingly.
    }

    public void removeDownstreamInterface(String ifname) {
        if (!started()) return;

        // TODO: Check cache for LinkProperties of ifname and, if present,
        // call removeDownstream() accordingly.
    }

    private boolean isOffloadDisabled() {
        final int defaultDisposition = mHwInterface.getDefaultTetherOffloadDisabled();
        return (Settings.Global.getInt(
                mContentResolver, TETHER_OFFLOAD_DISABLED, defaultDisposition) != 0);
    }

    private boolean started() {
        return mConfigInitialized && mControlInitialized;
    }

    private boolean pushUpstreamParameters() {
        if (mUpstreamLinkProperties == null) {
            return mHwInterface.setUpstreamParameters(null, null, null, null);
        }

        // A stacked interface cannot be an upstream for hardware offload.
        // Consequently, we examine only the primary interface name, look at
        // getAddresses() rather than getAllAddresses(), and check getRoutes()
        // rather than getAllRoutes().
        final String iface = mUpstreamLinkProperties.getInterfaceName();
        final ArrayList<String> v6gateways = new ArrayList<>();
        String v4addr = null;
        String v4gateway = null;

        for (InetAddress ip : mUpstreamLinkProperties.getAddresses()) {
            if (ip instanceof Inet4Address) {
                v4addr = ip.getHostAddress();
                break;
            }
        }

        // Find the gateway addresses of all default routes of either address family.
        for (RouteInfo ri : mUpstreamLinkProperties.getRoutes()) {
            if (!ri.hasGateway()) continue;

            final String gateway = ri.getGateway().getHostAddress();
            if (ri.isIPv4Default()) {
                v4gateway = gateway;
            } else if (ri.isIPv6Default()) {
                v6gateways.add(gateway);
            }
        }

        return mHwInterface.setUpstreamParameters(
                iface, v4addr, v4gateway, (v6gateways.isEmpty() ? null : v6gateways));
    }

    private boolean computeAndPushLocalPrefixes() {
        final Set<String> localPrefixStrs = computeLocalPrefixStrings(
                mExemptPrefixes, mUpstreamLinkProperties);
        if (mLastLocalPrefixStrs.equals(localPrefixStrs)) return true;

        mLastLocalPrefixStrs = localPrefixStrs;
        return mHwInterface.setLocalPrefixes(new ArrayList<>(localPrefixStrs));
    }

    // TODO: Factor in downstream LinkProperties once that information is available.
    private static Set<String> computeLocalPrefixStrings(
            Set<IpPrefix> localPrefixes, LinkProperties upstreamLinkProperties) {
        // Create an editable copy.
        final Set<IpPrefix> prefixSet = new HashSet<>(localPrefixes);

        // TODO: If a downstream interface (not currently passed in) is reusing
        // the /64 of the upstream (64share) then:
        //
        //     [a] remove that /64 from the local prefixes
        //     [b] add in /128s for IP addresses on the downstream interface
        //     [c] add in /128s for IP addresses on the upstream interface
        //
        // Until downstream information is available here, simply add /128s from
        // the upstream network; they'll just be redundant with their /64.
        if (upstreamLinkProperties != null) {
            for (LinkAddress linkAddr : upstreamLinkProperties.getLinkAddresses()) {
                if (!linkAddr.isGlobalPreferred()) continue;
                final InetAddress ip = linkAddr.getAddress();
                if (!(ip instanceof Inet6Address)) continue;
                prefixSet.add(new IpPrefix(ip, 128));
            }
        }

        final HashSet<String> localPrefixStrs = new HashSet<>();
        for (IpPrefix pfx : prefixSet) localPrefixStrs.add(pfx.toString());
        return localPrefixStrs;
    }
}
