package org.osmium.forensics;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

/**
 * Bridges GrimAC violations into the evidence DB.
 *
 * Grim 2.3.74+ exposes GrimAPIProvider.get() -> GrimAbstractAPI -> EventBus;
 * we subscribe to GrimCheckEvent via a dynamic proxy so Osmium never needs a
 * compile-time Grim dependency. Everything is reflective THROUGH the Grim
 * plugin's own classloader (the server classloader can't see Grim classes).
 *
 * Fail-open: any reflection failure just disables violation capture — packet
 * recording continues untouched.
 */
public final class OsmiumGrimBridge {

    private static final Logger LOGGER = Logger.getLogger("Osmium-Forensics");
    private static volatile boolean attached = false;
    private static Object channel; // GrimCheckEvent$Channel, for unregister
    private static Object ownerToken = new Object();

    private OsmiumGrimBridge() {}

    public static void start() {
        if (attached) return;
        try {
            Plugin grim = org.bukkit.Bukkit.getPluginManager().getPlugin("GrimAC");
            if (grim == null || !grim.isEnabled()) {
                LOGGER.info("GrimAC not present — violation capture disabled (packets still recorded)");
                return;
            }
            ClassLoader cl = grim.getClass().getClassLoader();
            Class<?> providerClass = Class.forName("ac.grim.grimac.api.GrimAPIProvider", true, cl);
            Method get = providerClass.getMethod("get");
            Object api = get.invoke(null);
            if (api == null) {
                // Grim loads its API async; try getAsync().thenRun? keep simple: retry on next tick via this call failing silently
                LOGGER.info("GrimAC API not ready yet — violation capture disabled for this session");
                return;
            }
            Method busMethod = api.getClass().getMethod("getEventBus");
            Object bus = busMethod.invoke(api);
            Class<?> busIface = Class.forName("ac.grim.grimac.api.event.EventBus", true, cl);
            Class<?> checkEventClass = Class.forName("ac.grim.grimac.api.event.events.GrimCheckEvent", true, cl);
            Object channelObj = busIface.getMethod("get", Class.class).invoke(bus, checkEventClass);
            Class<?> channelClass = Class.forName("ac.grim.grimac.api.event.events.GrimCheckEvent$Channel", true, cl);
            Class<?> handlerClass = Class.forName("ac.grim.grimac.api.event.events.GrimCheckEvent$Handler", true, cl);

            Object handler = Proxy.newProxyInstance(cl, new Class<?>[]{ handlerClass },
                    (proxy, method, args) -> {
                        if ("onCheck".equals(method.getName()) && args != null && args.length >= 3) {
                            handle(args[0], args[1], args[2]);
                            return false; // never cancel Grim's own handling
                        }
                        return deflt(method, args);
                    });
            channelClass.getMethod("onCheck", Object.class, handlerClass).invoke(channelObj, ownerToken, handler);
            channel = channelObj;
            attached = true;
            LOGGER.info("GrimAC violation capture attached");
        } catch (Throwable t) {
            LOGGER.info("GrimAC violation capture unavailable: " + t);
        }
    }

    public static void stop() {
        if (!attached) return;
        try {
            Class<?> channelClass = channel.getClass();
            Class<?> handlerClass = Class.forName("ac.grim.grimac.api.event.events.GrimCheckEvent$Handler",
                    true, OsmiumGrimBridge.class.getClassLoader());
            // find our handler via unsubscribeAllFromPlugin(owner)
            Method m = channelClass.getMethod("unsubscribeAllFromPlugin", Object.class);
            m.invoke(channel, ownerToken);
        } catch (Throwable ignored) {}
        attached = false;
    }

    // invoked on Grim's packet-processing thread — only enqueue, never block
    private static void handle(Object user, Object check, Object setbackObj) {
        try {
            Class<?> identity = Class.forName("ac.grim.grimac.api.GrimIdentity", true, user.getClass().getClassLoader());
            UUID uuid = (UUID) identity.getMethod("getUniqueId").invoke(user);
            String name;
            try { name = (String) user.getClass().getMethod("getName").invoke(user); }
            catch (Exception e) { name = ""; }
            Object checkObj = check;
            String checkName;
            try { checkName = (String) checkObj.getClass().getMethod("getCheckName").invoke(checkObj); }
            catch (Exception e) { checkName = "unknown"; }
            double vl;
            try { vl = (Double) checkObj.getClass().getMethod("getViolations").invoke(checkObj); }
            catch (Exception e) { vl = 0; }
            boolean setback = Boolean.TRUE.equals(setbackObj);
            OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.GrimRow(uuid.toString(), name,
                    System.currentTimeMillis(), checkName, vl, setback));
        } catch (Throwable ignored) {
            // never let evidence capture break Grim
        }
    }

    private static Object deflt(Method method, Object[] args) {
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) return false;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == double.class) return 0.0;
        if (rt == float.class) return 0.0f;
        return null;
    }
}
