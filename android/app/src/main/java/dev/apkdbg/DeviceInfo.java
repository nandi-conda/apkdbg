package dev.apkdbg;

import android.app.ActivityManager;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

/**
 * What the app can learn about the phone through Android APIs, written as
 * device_snapshot.json in the agent's working directory before it starts.
 * Much of this is out of reach from a shell inside an app sandbox, so the
 * snapshot fills the gaps the agent can't probe for itself.
 */
final class DeviceInfo {
    private DeviceInfo() { }

    static void writeSnapshot(Context c, File out) {
        JSONObject o = new JSONObject();
        put(o, "captured_at_ms", System.currentTimeMillis());
        put(o, "device", device());
        put(o, "battery", battery(c));
        put(o, "memory", memory(c));
        put(o, "storage", storage(c));
        put(o, "network", network(c));
        put(o, "power", power(c));
        put(o, "settings", settings(c));
        put(o, "permissions", permissions(c));
        put(o, "packages", packages(c));
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(Agent.TAG, "snapshot", e);
        }
    }

    private static JSONObject device() {
        JSONObject d = new JSONObject();
        put(d, "manufacturer", Build.MANUFACTURER);
        put(d, "brand", Build.BRAND);
        put(d, "model", Build.MODEL);
        put(d, "device", Build.DEVICE);
        put(d, "android_release", Build.VERSION.RELEASE);
        put(d, "sdk_int", Build.VERSION.SDK_INT);
        put(d, "security_patch", Build.VERSION.SECURITY_PATCH);
        put(d, "build_id", Build.DISPLAY);
        put(d, "fingerprint", Build.FINGERPRINT);
        put(d, "abis", new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS)));
        put(d, "hardware", Build.HARDWARE);
        put(d, "uptime_ms", SystemClock.elapsedRealtime());
        put(d, "timezone", TimeZone.getDefault().getID());
        return d;
    }

    private static JSONObject battery(Context c) {
        JSONObject b = new JSONObject();
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            put(b, "percent", scale > 0 ? level * 100 / scale : level);
            put(b, "status", batteryStatus(i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)));
            put(b, "health", batteryHealth(i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)));
            put(b, "plugged", plugged(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)));
            put(b, "temperature_c", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            put(b, "voltage_mv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0));
            put(b, "technology", i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));
            if (Build.VERSION.SDK_INT >= 34) {
                put(b, "cycle_count", i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1));
            }
        }
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            put(b, "current_now_ua", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
            put(b, "current_avg_ua", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));
            put(b, "charge_counter_uah", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
            put(b, "is_charging", bm.isCharging());
            if (Build.VERSION.SDK_INT >= 28) put(b, "charge_time_remaining_ms", bm.computeChargeTimeRemaining());
        }
        return b;
    }

    private static JSONObject memory(Context c) {
        JSONObject m = new JSONObject();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        put(m, "total_bytes", mi.totalMem);
        put(m, "available_bytes", mi.availMem);
        put(m, "low_memory", mi.lowMemory);
        put(m, "low_memory_threshold_bytes", mi.threshold);
        put(m, "memory_class_mb", am.getMemoryClass());
        put(m, "is_low_ram_device", am.isLowRamDevice());
        return m;
    }

    private static JSONObject storage(Context c) {
        JSONObject s = new JSONObject();
        StatFs data = new StatFs(Environment.getDataDirectory().getPath());
        put(s, "data_total_bytes", data.getTotalBytes());
        put(s, "data_free_bytes", data.getAvailableBytes());
        try {
            StorageStatsManager ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
            put(s, "internal_total_bytes", ssm.getTotalBytes(StorageManager.UUID_DEFAULT));
            put(s, "internal_free_bytes", ssm.getFreeBytes(StorageManager.UUID_DEFAULT));
        } catch (IOException | RuntimeException e) {
            put(s, "storage_stats_error", String.valueOf(e.getMessage()));
        }
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        JSONArray vols = new JSONArray();
        for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
            JSONObject vo = new JSONObject();
            put(vo, "description", v.getDescription(c));
            put(vo, "primary", v.isPrimary());
            put(vo, "removable", v.isRemovable());
            put(vo, "state", v.getState());
            vols.put(vo);
        }
        put(s, "volumes", vols);
        return s;
    }

    private static JSONObject network(Context c) {
        JSONObject n = new JSONObject();
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = cm.getActiveNetwork();
        put(n, "has_active_network", active != null);
        put(n, "metered", cm.isActiveNetworkMetered());
        put(n, "restrict_background_status", cm.getRestrictBackgroundStatus());
        JSONArray all = new JSONArray();
        for (Network net : cm.getAllNetworks()) {
            JSONObject no = new JSONObject();
            NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            LinkProperties lp = cm.getLinkProperties(net);
            put(no, "active", net.equals(active));
            if (nc != null) {
                JSONArray t = new JSONArray();
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t.put("wifi");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t.put("cellular");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t.put("ethernet");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t.put("vpn");
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) t.put("bluetooth");
                put(no, "transports", t);
                put(no, "internet", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                put(no, "validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                put(no, "captive_portal", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
                put(no, "not_metered", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
                put(no, "down_kbps", nc.getLinkDownstreamBandwidthKbps());
                put(no, "up_kbps", nc.getLinkUpstreamBandwidthKbps());
                if (Build.VERSION.SDK_INT >= 29) put(no, "signal_strength", nc.getSignalStrength());
            }
            if (lp != null) {
                put(no, "interface", lp.getInterfaceName());
                JSONArray dns = new JSONArray();
                for (java.net.InetAddress a : lp.getDnsServers()) dns.put(a.getHostAddress());
                put(no, "dns_servers", dns);
                if (Build.VERSION.SDK_INT >= 28) {
                    put(no, "private_dns_active", lp.isPrivateDnsActive());
                    put(no, "private_dns_server", lp.getPrivateDnsServerName());
                }
                JSONArray addrs = new JSONArray();
                for (android.net.LinkAddress la : lp.getLinkAddresses()) addrs.put(la.toString());
                put(no, "addresses", addrs);
            }
            all.put(no);
        }
        put(n, "networks", all);
        return n;
    }

    private static JSONObject power(Context c) {
        JSONObject p = new JSONObject();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        put(p, "power_save_mode", pm.isPowerSaveMode());
        put(p, "device_idle_mode", pm.isDeviceIdleMode());
        put(p, "interactive", pm.isInteractive());
        put(p, "ignoring_battery_optimizations_for_this_app", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        if (Build.VERSION.SDK_INT >= 29) put(p, "thermal_status", thermal(pm.getCurrentThermalStatus()));
        if (Build.VERSION.SDK_INT >= 30) put(p, "thermal_headroom_10s", pm.getThermalHeadroom(10));
        return p;
    }

    private static JSONObject settings(Context c) {
        JSONObject s = new JSONObject();
        android.content.ContentResolver r = c.getContentResolver();
        put(s, "airplane_mode", Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        put(s, "screen_brightness", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, -1));
        put(s, "brightness_auto", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == 1);
        put(s, "screen_off_timeout_ms", Settings.System.getInt(r, Settings.System.SCREEN_OFF_TIMEOUT, -1));
        put(s, "auto_time", Settings.Global.getInt(r, Settings.Global.AUTO_TIME, -1));
        put(s, "adb_enabled", Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) == 1);
        put(s, "developer_options", Settings.Global.getInt(r, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1);
        put(s, "animator_duration_scale", Settings.Global.getFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, 1f));
        return s;
    }

    private static JSONObject permissions(Context c) {
        JSONObject p = new JSONObject();
        for (String perm : new String[] {"android.permission.READ_LOGS", "android.permission.DUMP",
                "android.permission.PACKAGE_USAGE_STATS"}) {
            put(p, perm.substring(perm.lastIndexOf('.') + 1),
                    c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED);
        }
        return p;
    }

    private static JSONObject packages(Context c) {
        JSONObject o = new JSONObject();
        PackageManager pm = c.getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
        int user = 0, system = 0;
        for (android.content.pm.ApplicationInfo a : apps) {
            if ((a.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) system++; else user++;
        }
        put(o, "user_installed", user);
        put(o, "system", system);
        put(o, "note", "Run `pm list packages -3` for the user-installed package names.");
        return o;
    }

    private static String batteryStatus(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            default: return "unknown";
        }
    }

    private static String batteryHealth(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over_voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
            default: return "unknown";
        }
    }

    private static String plugged(int p) {
        if (p == 0) return "unplugged";
        if ((p & BatteryManager.BATTERY_PLUGGED_AC) != 0) return "ac";
        if ((p & BatteryManager.BATTERY_PLUGGED_USB) != 0) return "usb";
        if ((p & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) return "wireless";
        return "other";
    }

    private static String thermal(int t) {
        switch (t) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "unknown";
        }
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v == null ? JSONObject.NULL : v); } catch (JSONException ignored) { }
    }
}
