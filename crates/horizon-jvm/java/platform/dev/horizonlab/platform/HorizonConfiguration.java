package dev.horizonlab.platform;

import dev.horizonlab.bridge.Natives;
import java.lang.reflect.Field;
import java.util.Queue;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * A joining player's configuration phase, as a vanilla server runs one before play: the mods'
 * part of it. Mods send their client the data it needs before the world (a weapon registry,
 * their settings) from Fabric's ServerConfigurationConnectionEvents, as configuration tasks
 * whose payloads the client's configuration receivers answer. The mirror's players join
 * straight into play, so here, with the headless client in this JVM: a configuration listener
 * for the player (vanilla's, made by its constructor, so Fabric's networking state is there),
 * Fabric's two configure events, then each task the mods added started in turn, its payloads
 * delivered to the client's receivers as that player's client, the answers to the server's,
 * until the task is done (ServerConfigurationPacketListenerImpl.completeTask). Vanilla's own
 * tasks (registries, resource packs, joining the world) are Horizon's and aren't added; Fabric's
 * channel handshake is left out (one JVM).
 */
public final class HorizonConfiguration {
    private HorizonConfiguration() {}

    /** HORIZON_JAVA_DEBUG=loop: each configuration task and payload is logged. */
    private static final boolean DEBUG = String.valueOf(System.getenv("HORIZON_JAVA_DEBUG")).contains("loop");

    private static Field tasks, current, sendable;
    /** The player configuring now and their listener (ConnectionMixin: what Fabric sends the connection). */
    private static ServerPlayer configuring;
    private static ServerConfigurationPacketListenerImpl configuringListener;
    private static final java.util.Set<Object> SET_UP = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static boolean failedOnce;

    /** Runs the mods' configuration phase for {@code player}, joining on {@code connection}. */
    public static void configure(MinecraftServer server, ServerPlayer player, net.minecraft.network.Connection connection) {
        if (!HorizonLoop.on()) return;
        try {
            if (tasks == null) {
                Field t = ServerConfigurationPacketListenerImpl.class.getDeclaredField("configurationTasks");
                Field c = ServerConfigurationPacketListenerImpl.class.getDeclaredField("currentTask");
                t.setAccessible(true);
                c.setAccessible(true);
                current = c;
                tasks = t;
            }
            // The mirror's connection was made without its constructor: its mods' parts
            // (Fabric's channel lists) are made as at its end, once.
            synchronized (SET_UP) {
                if (SET_UP.add(connection)) {
                    HorizonHooks.initFields(connection);
                    HorizonHooks.constructed(connection);
                    // A closed memory channel, as the client's connection has (HorizonClient.memoryConnection):
                    // mods reach the connection's channel while configuring (Multiloader Data Extensions'
                    // data-map reply: connection.channel.pipeline()), and a null one failed their task.
                    // (UMP, 2026-11-14)
                    try {
                        Field ch = net.minecraft.network.Connection.class.getDeclaredField("channel");
                        ch.setAccessible(true);
                        if (ch.get(connection) == null) HorizonClient.memoryConnection(connection);
                    } catch (ReflectiveOperationException e) {
                        Natives.log("Java mods: a joining player's connection has no channel: " + e);
                    }
                }
            }
            var listener = new ServerConfigurationPacketListenerImpl(server, connection,
                    CommonListenerCookie.createInitial(player.getGameProfile(), false));
            var addon = ServerNetworkingImpl.getAddon(listener);
            // What the client can receive: its configuration receivers (it would register them).
            if (sendable == null) {
                Field f = net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon.class.getDeclaredField("sendableChannels");
                f.setAccessible(true);
                sendable = f;
            }
            @SuppressWarnings("unchecked")
            var channels = (java.util.Set<net.minecraft.resources.Identifier>) sendable.get(addon);
            channels.addAll(ClientNetworkingImpl.CONFIGURATION.getChannels());
            // ServerConfigurationPacketListenerImpl.startConfiguration's (Fabric's injections):
            // BEFORE_CONFIGURE, then CONFIGURE. The mods add their tasks there.
            configuring = player;
            configuringListener = listener;
            try {
                run(server, player, listener, addon);
            } finally {
                configuring = null;
                configuringListener = null;
            }
        } catch (Throwable t) {
            log("a player's configuration failed", t);
        }
    }

    private static void run(MinecraftServer server, ServerPlayer player, ServerConfigurationPacketListenerImpl listener,
                            net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon addon) throws ReflectiveOperationException {
        {
            addon.preConfiguration();
            addon.configuration();
            @SuppressWarnings("unchecked")
            Queue<ConfigurationTask> queue = (Queue<ConfigurationTask>) tasks.get(listener);
            int ran = 0;
            for (ConfigurationTask task; (task = queue.poll()) != null; ran++) {
                // Fabric's own channel handshake (c:version, c:register): both halves are in
                // this JVM and know each other's channels; its client half has no addon here.
                if (task.getClass().getName().startsWith("net.fabricmc.fabric.impl.networking.")) {
                    ran--;
                    continue;
                }
                if (DEBUG) Natives.log("loop: configuration task " + task.type().id() + " for " + player.getName().getString());
                current.set(listener, task);
                try {
                    task.start(packet -> toClient(server, player, listener, packet));
                    HorizonServer.runTasks();
                } catch (Throwable t) {
                    log("the configuration task " + task.type().id() + " failed", t);
                }
                // Not done once its messages were answered: it waits on something the mirror
                // doesn't have. The next one starts anyway (a real client would time out).
                if (current.get(listener) == task) {
                    Natives.log("Java mods: the configuration task " + task.type().id() + " didn't finish; joining anyway");
                    current.set(listener, null);
                }
            }
            if (ran > 0) Natives.log("Java mods: " + player.getName().getString() + "'s configuration: " + ran + " task(s)");
        }
    }

    /**
     * A packet sent on a mirrored connection (ConnectionMixin): during a configuration, what
     * Fabric's own tasks send the connection directly goes to the client as a task's would;
     * else it is dropped (the mirror's connection has no channel). Whether it was taken.
     */
    public static boolean sent(Packet<?> packet) {
        ServerPlayer player = configuring;
        if (player != null) toClient(HorizonServer.server(), player, configuringListener, packet);
        return true;
    }

    /** A packet a task sends the client: a mod's payload goes to the client's configuration receiver. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void toClient(MinecraftServer server, ServerPlayer player, ServerConfigurationPacketListenerImpl listener, Packet<?> packet) {
        if (!(packet instanceof ClientboundCustomPayloadPacket(CustomPacketPayload payload))) return;
        var handler = (ClientConfigurationNetworking.ConfigurationPayloadHandler) ClientNetworkingImpl.CONFIGURATION.getHandler(payload.type().id());
        if (DEBUG) Natives.log("loop: configuration, server to client " + payload.type().id() + (handler == null ? " (no receiver)" : ""));
        if (handler == null) return;
        try {
            HorizonClient.as(player, () -> {
                handler.receive(carried(payload, net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION),
                        clientContext(server, listener));
                HorizonClient.tasks();
            });
        } catch (Throwable t) {
            log("the client of " + payload.type().id() + " failed", t);
        }
    }

    /** A payload the client answers with: the server's configuration receivers (global or the listener's). */
    private static void toServer(ServerConfigurationPacketListenerImpl listener, CustomPacketPayload payload) {
        if (DEBUG) Natives.log("loop: configuration, client to server " + payload.type().id());
        try {
            ServerNetworkingImpl.getAddon(listener).handle(
                    carried(payload, net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION));
            HorizonServer.runTasks();
        } catch (Throwable t) {
            log("the server side of " + payload.type().id() + " failed", t);
        }
    }

    private static ClientConfigurationNetworking.Context clientContext(MinecraftServer server, ServerConfigurationPacketListenerImpl listener) {
        PacketSender sender = new PacketSender() {
            @Override
            public Packet<?> createPacket(CustomPacketPayload payload) {
                return new ServerboundCustomPayloadPacket(payload);
            }

            @Override
            public void sendPacket(Packet<?> packet, io.netty.channel.ChannelFutureListener callback) {
                if (packet instanceof ServerboundCustomPayloadPacket(CustomPacketPayload payload)) toServer(listener, payload);
            }

            @Override
            public void sendPacket(CustomPacketPayload payload) {
                toServer(listener, payload);
            }

            @Override
            public void disconnect(net.minecraft.network.chat.Component reason) {
                Natives.log("Java mods: a mod's client asked to leave during its configuration: " + reason.getString());
            }
        };
        return new ClientConfigurationNetworking.Context() {
            @Override
            public Minecraft client() {
                return Minecraft.getInstance();
            }

            @Override
            public net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl packetListener() {
                return null;
            }

            @Override
            public PacketSender responseSender() {
                return sender;
            }
        };
    }

    /**
     * The payload as the other side reads it: written and read back with its configuration
     * codec, as over a connection (a mod's set the receiver changes is then its own). One whose
     * codec fails here goes over as it is.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CustomPacketPayload carried(CustomPacketPayload payload,
                                               net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl<net.minecraft.network.FriendlyByteBuf> registry) {
        var type = registry.get(payload.type().id());
        if (type == null) return payload;
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            ((net.minecraft.network.codec.StreamCodec) type.codec()).encode(buf, payload);
            Object read = ((net.minecraft.network.codec.StreamCodec) type.codec()).decode(buf);
            if (read instanceof CustomPacketPayload back) return back;
        } catch (Throwable t) {
            if (DEBUG) Natives.log("loop: configuration payload " + payload.type().id() + " goes over as it is (its codec: " + t + ")");
        } finally {
            buf.release();
        }
        return payload;
    }

    private static void log(String what, Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        Natives.log("Java mods: " + what + ": " + root);
        HorizonFailures.report("a player's configuration", root);
        if (!failedOnce) {
            failedOnce = true;
            for (StackTraceElement frame : root.getStackTrace()) Natives.log("    at " + frame);
        }
    }
}
