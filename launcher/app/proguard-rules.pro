# Started by class name from the shell (app_process -cp <apk> ... MediaHelper), never referenced.
-keep class my.rafhan.app.tvhome.MediaHelper {
    public static void main(java.lang.String[]);
}
# JNI callbacks invoked by the local traffic monitor.
-keep class my.rafhan.app.tvhome.TrafficVpnService { *; }
