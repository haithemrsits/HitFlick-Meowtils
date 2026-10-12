import wtf.tatp.meowtils.extension.Extension;

/**
 * Entry point required by Meowtils: a class named Main with a
 * public static void init(). One module per extension file, so we only
 * register HitFlick. Module classes are hooked into the event system
 * automatically; no Extension.registerEvent() call is needed for them.
 */
public class Main {
    public static void init() {
        Extension.registerModule(new HitFlick());
    }
}
