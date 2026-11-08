package io.github.up9cloud.td;

// JNI binding for libtdjson.so from up9cloud/android-libtdjson. The package/class name must
// not change: it is part of the native symbol names.
public final class JsonClient {
    static {
        try {
            System.loadLibrary("tdjson");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
        }
    }
    public static native int td_create_client_id();
    public static native void td_send(int client_id, String request);
    public static native String td_receive(double timeout);
    public static native String td_execute(String request);
}
