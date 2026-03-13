package org.osmium.anticheat.grim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.init.Initable;
import ac.grim.grimac.manager.init.start.ExemptOnlinePlayersOnReload;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.initables.BukkitEventManager;
import ac.grim.grimac.platform.bukkit.initables.BukkitBStats;
import ac.grim.grimac.platform.bukkit.initables.BukkitTickEndEvent;
import ac.grim.grimac.platform.bukkit.manager.BukkitMessagePlaceHolderManager;
import ac.grim.grimac.platform.bukkit.utils.placeholder.PlaceholderAPIExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrates GrimAC lifecycle as an embedded server module (not a plugin).
 * Creates a JavaPlugin shim so GrimAC's Bukkit integration works without
 * going through the plugin jar loading system.
 */
public class OsmiumGrimLoader {

    private static final Logger LOGGER = Logger.getLogger("Osmium-GrimAC");
    private static boolean initialized = false;

    /**
     * Phase 1: Called early in server init after OsmiumConfig.init().
     * Creates the JavaPlugin shim and calls GrimAPI.load().
     */
    public static void init() {
        try {
            LOGGER.info("Initializing embedded GrimAC anticheat...");

            // Create the plugin shim via reflection
            GrimACBukkitLoaderPlugin loader = createPluginShim();
            GrimACBukkitLoaderPlugin.LOADER = loader;

            // Register with Bukkit's plugin manager so other systems can find it
            registerWithBukkit(loader);

            // Build init tasks (same as GrimACBukkitLoaderPlugin.onLoad() would)
            Initable[] initTasks = new Initable[] {
                new ExemptOnlinePlayersOnReload(),
                new BukkitEventManager(),
                new BukkitTickEndEvent(),
                new BukkitBStats(), // no-op'd for embedded use
                (StartableInitable) () -> {
                    if (BukkitMessagePlaceHolderManager.hasPlaceholderAPI) {
                        new PlaceholderAPIExpansion().register();
                    }
                }
            };

            // Load GrimAC
            GrimAPI.INSTANCE.load(loader, initTasks);
            initialized = true;
            LOGGER.info("GrimAC loaded successfully.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize GrimAC", e);
        }
    }

    /**
     * Phase 2: Called after POSTWORLD plugin enable.
     * Starts GrimAC checks and commands.
     */
    public static void start() {
        if (!initialized) return;
        try {
            GrimAPI.INSTANCE.start();
            GrimACBukkitLoaderPlugin.LOADER.registerAPIService();
            LOGGER.info("GrimAC started successfully.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start GrimAC", e);
        }
    }

    /**
     * Phase 3: Called during server shutdown.
     */
    public static void stop() {
        if (!initialized) return;
        try {
            GrimAPI.INSTANCE.stop();
            LOGGER.info("GrimAC stopped.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to stop GrimAC cleanly", e);
        }
    }

    /**
     * Creates a GrimACBukkitLoaderPlugin instance via reflection, calling
     * JavaPlugin.init() to properly initialize it without the plugin jar system.
     */
    private static GrimACBukkitLoaderPlugin createPluginShim() throws Exception {
        // Build a minimal PluginDescriptionFile
        PluginDescriptionFile description = new PluginDescriptionFile(
            "GrimAC", "2.3.74-osmium", "ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin"
        );

        File dataFolder = new File("grim");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        // Use a dummy jar file reference (embedded, no actual jar)
        File dummyFile = new File("grim", "grimac-embedded.jar");

        // Use the 5-param init() that doesn't need a PluginLoader
        // (Paper's JavaPlugin ignores the loader param anyway and uses DummyPluginLoaderImplHolder)
        java.lang.reflect.Method initMethod = JavaPlugin.class.getDeclaredMethod(
            "init",
            org.bukkit.Server.class,
            PluginDescriptionFile.class,
            File.class,
            File.class,
            ClassLoader.class,
            io.papermc.paper.plugin.configuration.PluginMeta.class,
            java.util.logging.Logger.class
        );
        initMethod.setAccessible(true);

        // Construct the instance without calling the constructor that requires
        // BukkitResolverRegistrar (which needs GrimAPI.INSTANCE.getExtensionManager() to be ready)
        // We use Unsafe to allocate the instance, then init() it
        sun.misc.Unsafe unsafe = getUnsafe();
        GrimACBukkitLoaderPlugin loader = (GrimACBukkitLoaderPlugin) unsafe.allocateInstance(GrimACBukkitLoaderPlugin.class);

        // Initialize as a JavaPlugin using the 7-param init
        initMethod.invoke(loader,
            Bukkit.getServer(),
            description,
            dataFolder,
            dummyFile,
            GrimACBukkitLoaderPlugin.class.getClassLoader(),
            description, // PluginMeta (PluginDescriptionFile implements PluginMeta)
            java.util.logging.Logger.getLogger("GrimAC")
        );

        // Now manually do what the constructor does, but after init() so getLogger() etc. work
        ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar registrar =
            new ac.grim.grimac.internal.platform.bukkit.resolver.BukkitResolverRegistrar();
        registrar.registerAll(GrimAPI.INSTANCE.getExtensionManager());

        // Set the plugin field via reflection (it's final from Lombok @Getter)
        java.lang.reflect.Field pluginField = GrimACBukkitLoaderPlugin.class.getDeclaredField("plugin");
        pluginField.setAccessible(true);
        pluginField.set(loader, registrar.resolvePlugin(loader));

        // Initialize lazy holder fields that the constructor creates
        initLazyFields(loader);

        return loader;
    }

    /**
     * Initialize the LazyHolder fields that would normally be created by the constructor.
     * Since we used Unsafe.allocateInstance(), these fields are null.
     */
    private static void initLazyFields(GrimACBukkitLoaderPlugin loader) throws Exception {
        // We need to set the LazyHolder fields and the other final fields
        // that would be initialized inline in the class declaration
        setField(loader, "scheduler",
            ac.grim.grimac.utils.lazy.LazyHolder.simple(() -> createScheduler()));
        setField(loader, "packetEvents",
            ac.grim.grimac.utils.lazy.LazyHolder.simple(() ->
                io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder.build(loader)));
        setField(loader, "senderFactory",
            ac.grim.grimac.utils.lazy.LazyHolder.simple(ac.grim.grimac.platform.bukkit.sender.BukkitSenderFactory::new));
        setField(loader, "itemResetHandler",
            ac.grim.grimac.utils.lazy.LazyHolder.simple(ac.grim.grimac.platform.bukkit.manager.BukkitItemResetHandler::new));
        setField(loader, "commandService",
            ac.grim.grimac.utils.lazy.LazyHolder.simple(() -> createCommandService(loader)));
        setField(loader, "commandAdapter",
            new ac.grim.grimac.platform.bukkit.manager.BukkitParserDescriptorFactory());
        setField(loader, "platformPlayerFactory",
            new ac.grim.grimac.platform.bukkit.player.BukkitPlatformPlayerFactory());
        setField(loader, "pluginManager",
            new ac.grim.grimac.platform.bukkit.manager.BukkitPlatformPluginManager());
        setField(loader, "platformServer",
            new ac.grim.grimac.platform.bukkit.BukkitPlatformServer());
        setField(loader, "messagePlaceHolderManager",
            new ac.grim.grimac.platform.bukkit.manager.BukkitMessagePlaceHolderManager());
        setField(loader, "permissionManager",
            new ac.grim.grimac.platform.bukkit.manager.BukkitPermissionRegistrationManager());
    }

    private static ac.grim.grimac.platform.api.scheduler.PlatformScheduler createScheduler() {
        return GrimAPI.INSTANCE.getPlatform() == ac.grim.grimac.platform.api.Platform.FOLIA
            ? new ac.grim.grimac.platform.bukkit.scheduler.folia.FoliaPlatformScheduler()
            : new ac.grim.grimac.platform.bukkit.scheduler.bukkit.BukkitPlatformScheduler();
    }

    private static ac.grim.grimac.platform.api.command.CommandService createCommandService(GrimACBukkitLoaderPlugin loader) {
        try {
            return new ac.grim.grimac.command.CloudCommandService(
                () -> createCloudCommandManager(loader),
                new ac.grim.grimac.platform.bukkit.manager.BukkitParserDescriptorFactory()
            );
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to initialize GrimAC command framework. Commands will be unavailable.", t);
            return () -> {};
        }
    }

    private static org.incendo.cloud.CommandManager<ac.grim.grimac.platform.api.sender.Sender> createCloudCommandManager(GrimACBukkitLoaderPlugin loader) {
        org.incendo.cloud.paper.LegacyPaperCommandManager<ac.grim.grimac.platform.api.sender.Sender> manager =
            new org.incendo.cloud.paper.LegacyPaperCommandManager<>(
                loader,
                org.incendo.cloud.execution.ExecutionCoordinator.simpleCoordinator(),
                loader.getBukkitSenderFactory()
            );
        if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            try {
                manager.registerBrigadier();
                org.incendo.cloud.brigadier.CloudBrigadierManager<ac.grim.grimac.platform.api.sender.Sender, ?> cbm = manager.brigadierManager();
                cbm.settings().set(org.incendo.cloud.brigadier.BrigadierSetting.FORCE_EXECUTABLE, true);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Failed to register Brigadier for GrimAC commands.", t);
            }
        } else if (manager.hasCapability(org.incendo.cloud.bukkit.CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
        return manager;
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    private static sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    /**
     * Register the plugin shim with Bukkit's plugin manager so APIs like
     * Bukkit.getPluginManager().getPlugin("GrimAC") work.
     */
    private static void registerWithBukkit(GrimACBukkitLoaderPlugin loader) {
        try {
            org.bukkit.plugin.PluginManager pm = Bukkit.getPluginManager();
            if (pm instanceof org.bukkit.plugin.SimplePluginManager) {
                java.lang.reflect.Field pluginsField = org.bukkit.plugin.SimplePluginManager.class.getDeclaredField("plugins");
                pluginsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List<org.bukkit.plugin.Plugin> plugins = (java.util.List<org.bukkit.plugin.Plugin>) pluginsField.get(pm);
                plugins.add(loader);

                java.lang.reflect.Field lookupField = org.bukkit.plugin.SimplePluginManager.class.getDeclaredField("lookupNames");
                lookupField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, org.bukkit.plugin.Plugin> lookupNames = (java.util.Map<String, org.bukkit.plugin.Plugin>) lookupField.get(pm);
                lookupNames.put("GrimAC", loader);
                lookupNames.put("grimac", loader);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not register GrimAC with Bukkit plugin manager", e);
        }
    }
}
