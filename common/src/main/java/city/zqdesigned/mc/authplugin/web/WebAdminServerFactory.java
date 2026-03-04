package city.zqdesigned.mc.authplugin.web;

import city.zqdesigned.mc.authplugin.auth.AuthService;
import city.zqdesigned.mc.authplugin.config.WebConfig;
import city.zqdesigned.mc.authplugin.profile.PlayerProfileService;
import city.zqdesigned.mc.authplugin.token.TokenService;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class WebAdminServerFactory {
    private static final List<String> IMPLEMENTATION_CLASSES = List.of(
        "city.zqdesigned.mc.authplugin.neoforge.web.NeoForgeWebAdminServer",
        "city.zqdesigned.mc.authplugin.fabric.web.FabricWebAdminServer",
        "city.zqdesigned.mc.authplugin.web.WebAdminServer"
    );

    private static final List<String> PLATFORM_ENTRYPOINT_CLASSES = List.of(
        "city.zqdesigned.mc.authplugin.neoforge.AuthPluginNeoForge",
        "city.zqdesigned.mc.authplugin.fabric.AuthPluginFabric"
    );

    private WebAdminServerFactory() {
    }

    public static WebAdminLifecycle create(
        TokenService tokenService,
        AuthService authService,
        OnlinePlayerRegistry onlinePlayerRegistry,
        PlayerProfileService playerProfileService,
        WebConfig webConfig
    ) {
        Throwable lastFailure = null;
        for (ClassLoader classLoader : collectCandidateClassLoaders()) {
            for (String className : IMPLEMENTATION_CLASSES) {
                try {
                    WebAdminLifecycle lifecycle = instantiate(
                        classLoader,
                        className,
                        tokenService,
                        authService,
                        onlinePlayerRegistry,
                        playerProfileService,
                        webConfig
                    );
                    if (lifecycle != null) {
                        return lifecycle;
                    }
                } catch (ReflectiveOperationException | LinkageError exception) {
                    lastFailure = exception;
                }
            }
        }

        if (lastFailure != null) {
            throw new IllegalStateException("Failed to construct a web admin server implementation", lastFailure);
        }

        throw new IllegalStateException("No available web admin server implementation found");
    }

    private static WebAdminLifecycle instantiate(
        ClassLoader classLoader,
        String className,
        TokenService tokenService,
        AuthService authService,
        OnlinePlayerRegistry onlinePlayerRegistry,
        PlayerProfileService playerProfileService,
        WebConfig webConfig
    ) throws ReflectiveOperationException {
        Class<?> implementationClass;
        try {
            implementationClass = Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException ignored) {
            return null;
        }

        Object instance;
        try {
            instance = implementationClass.getConstructor(
                TokenService.class,
                AuthService.class,
                OnlinePlayerRegistry.class,
                PlayerProfileService.class,
                WebConfig.class
            ).newInstance(tokenService, authService, onlinePlayerRegistry, playerProfileService, webConfig);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflectiveOperationException) {
                throw reflectiveOperationException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }

        if (instance instanceof WebAdminLifecycle lifecycle) {
            return lifecycle;
        }

        throw new IllegalStateException(
            "Class " + className + " does not implement " + WebAdminLifecycle.class.getName()
        );
    }

    private static List<ClassLoader> collectCandidateClassLoaders() {
        LinkedHashSet<ClassLoader> candidates = new LinkedHashSet<>();
        candidates.add(Thread.currentThread().getContextClassLoader());
        candidates.add(WebAdminServerFactory.class.getClassLoader());
        candidates.add(ClassLoader.getSystemClassLoader());

        for (String platformClass : PLATFORM_ENTRYPOINT_CLASSES) {
            for (ClassLoader loader : nonNullSnapshot(candidates)) {
                try {
                    Class<?> clazz = Class.forName(platformClass, false, loader);
                    candidates.add(clazz.getClassLoader());
                } catch (ClassNotFoundException ignored) {
                    // Ignore unavailable platform classes.
                }
            }
        }

        List<ClassLoader> snapshot = nonNullSnapshot(candidates);
        for (ClassLoader loader : snapshot) {
            ClassLoader parent = loader.getParent();
            while (parent != null) {
                candidates.add(parent);
                parent = parent.getParent();
            }
        }

        return nonNullSnapshot(candidates);
    }

    private static List<ClassLoader> nonNullSnapshot(Set<ClassLoader> candidates) {
        return candidates.stream()
            .filter(Objects::nonNull)
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
}
