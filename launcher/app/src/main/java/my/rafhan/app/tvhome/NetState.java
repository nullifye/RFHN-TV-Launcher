package my.rafhan.app.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/** Connected / connected without internet / disconnected, plus the network's name. */
final class NetState {
    static final int CONNECTED = 0, NO_INTERNET = 1, DISCONNECTED = 2;
    static final int GREEN = 0xFF63D489, AMBER = 0xFFFFB340, RED = 0xFFFF5A52;

    final int state;
    final String name;   // Wi-Fi name, "Ethernet", or null when disconnected
    final boolean wifi;

    private NetState(int state, String name, boolean wifi) {
        this.state = state;
        this.name = name;
        this.wifi = wifi;
    }

    /**
     * The network the TV is really on: the active one, or, while a VPN is up (Net traffic's, or any
     * other), the Wi-Fi or Ethernet under it, so its address and name are the TV's, not the VPN's.
     */
    @SuppressWarnings("deprecation") // getAllNetworks: its replacement needs a callback; this is a one-off look
    static Network physical(ConnectivityManager cm) {
        Network active = cm.getActiveNetwork();
        NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
        if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n;
        }
        return active;
    }

    static NetState read(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm.getNetworkCapabilities(physical(cm));
        if (nc == null) return new NetState(DISCONNECTED, null, false);
        boolean wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        String name;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            name = "Ethernet";
        } else if (wifi) {
            WifiInfo info = ctx.getApplicationContext().getSystemService(WifiManager.class).getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            name = ssid == null || ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid)
                    ? ctx.getString(R.string.wifi) : ssid.replaceAll("^\"|\"$", "");
        } else {
            name = "Connected";
        }
        // VALIDATED: Android's own internet check passed (also fails behind a captive portal).
        boolean internet = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        return new NetState(internet ? CONNECTED : NO_INTERNET, name, wifi);
    }

    int color() {
        return state == CONNECTED ? GREEN : state == NO_INTERNET ? AMBER : RED;
    }

    int icon() {
        if (state == DISCONNECTED) return R.drawable.ic_wifi_off;
        if (state == NO_INTERNET) return R.drawable.ic_wifi_alert;
        return R.drawable.ic_wifi;
    }

    /** Wi-Fi pill text. */
    String pillLabel() {
        if (state == DISCONNECTED) return "Disconnected";
        // Short, so the pill row (which sizes the System card) doesn't grow; colour and icon say the rest.
        return state == NO_INTERNET ? "No internet" : name;
    }

    /** Banner text on a change; {@code wasWifi}: what the lost connection was. */
    String bannerLabel(boolean wasWifi) {
        String kind = wifi || (state == DISCONNECTED && wasWifi) ? "Wi-Fi" : "Network";
        if (state == DISCONNECTED) return kind + " disconnected";
        if (state == NO_INTERNET) return name + " · no internet";
        return kind + " connected · " + name;
    }
}
