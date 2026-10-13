import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.channels.*;
import java.util.*;

/**
 * Physical-device regression: keep the app APK off dalvikvm's system classpath and
 * load it through DexClassLoader, as Android apps do. The old parser fails here even
 * though ordinary desktop tests and APK-on-system-classpath probes pass.
 * Compile this runner against android.jar, convert it with D8, and run with only
 * the runner JAR on dalvikvm's classpath. Arguments: APK, dex cache directory,
 * video-only fixture, fixture containing a subtitle track, output MP4.
 */
public class AndroidParserLoaderCheck {
    public static void main(String[] args) throws Exception {
        if (ClassLoader.getSystemResourceAsStream("isoparser2-default.properties") != null)
            throw new AssertionError("Test must isolate app resources from system loader");
        ClassLoader loader = new DexClassLoader(args[0], args[1], null, AndroidParserLoaderCheck.class.getClassLoader());
        Class<?> helper = loader.loadClass("com.example.javbrowser.EmbeddedSubtitles");
        Object instance = helper.getField("INSTANCE").get(null);
        Method read = helper.getMethod("read", FileChannel.class);
        List<?> subtitles;
        try (FileInputStream input = new FileInputStream(args[3])) {
            subtitles = (List<?>) read.invoke(instance, input.getChannel());
        }
        if (subtitles.size() != 1) throw new AssertionError("Missing subtitle track");
        Class<?> callback = loader.loadClass("kotlin.jvm.functions.Function0");
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object checkCanceled = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, values) -> unit);
        Method mux = helper.getMethod("mux", FileChannel.class, WritableByteChannel.class, List.class, callback);
        try (FileInputStream input = new FileInputStream(args[2]); FileOutputStream output = new FileOutputStream(args[4])) {
            mux.invoke(instance, input.getChannel(), output.getChannel(), subtitles, checkCanceled);
        }
        try (FileInputStream input = new FileInputStream(args[4])) {
            if (((List<?>) read.invoke(instance, input.getChannel())).size() != 1) throw new AssertionError("Muxed subtitles missing");
        }
        System.out.println("Isolated Android app-loader subtitle mux/read passed");
    }
}
