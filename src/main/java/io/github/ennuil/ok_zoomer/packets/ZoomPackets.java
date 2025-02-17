package io.github.ennuil.ok_zoomer.packets;

import org.quiltmc.qsl.networking.api.PacketByteBufs;
import org.quiltmc.qsl.networking.api.ServerPlayConnectionEvents;
import org.quiltmc.qsl.networking.api.client.ClientPlayConnectionEvents;
import org.quiltmc.qsl.networking.api.client.ClientPlayNetworking;

import io.github.ennuil.ok_zoomer.config.OkZoomerConfigManager;
import io.github.ennuil.ok_zoomer.config.ConfigEnums.CinematicCameraOptions;
import io.github.ennuil.ok_zoomer.config.ConfigEnums.SpyglassDependency;
import io.github.ennuil.ok_zoomer.config.ConfigEnums.ZoomOverlays;
import io.github.ennuil.ok_zoomer.utils.ZoomUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/* 	Manages the zoom packets and their signals.
	These packets are intended to be used by the future "Zoomer Boomer" server-side mod,
	although developers are welcome to independently transmit them for other loaders */
public class ZoomPackets {
	// The IDs for packets that allows the server to have some control on the zoom.
	public static final Identifier DISABLE_ZOOM_PACKET_ID = new Identifier("ok_zoomer", "disable_zoom");
	public static final Identifier DISABLE_ZOOM_SCROLLING_PACKET_ID = new Identifier("ok_zoomer", "disable_zoom_scrolling");
	public static final Identifier FORCE_CLASSIC_MODE_PACKET_ID = new Identifier("ok_zoomer", "force_classic_mode");
	public static final Identifier FORCE_ZOOM_DIVISOR_PACKET_ID = new Identifier("ok_zoomer", "force_zoom_divisor");
	public static final Identifier ACKNOWLEDGE_MOD_PACKET_ID = new Identifier("ok_zoomer", "acknowledge_mod");
	public static final Identifier FORCE_SPYGLASS_PACKET_ID = new Identifier("ok_zoomer", "force_spyglass");
	public static final Identifier FORCE_SPYGLASS_OVERLAY_PACKET_ID = new Identifier("ok_zoomer", "force_spyglass_overlay");

	public enum Acknowledgement {
		NONE,
		HAS_RESTRICTIONS,
		HAS_NO_RESTRICTIONS
	}

	// The signals used by other parts of the zoom in order to enforce the packets
	private static boolean hasRestrictions = false;
	private static boolean disableZoom = false;
	private static boolean disableZoomScrolling = false;
	private static boolean forceClassicMode = false;
	private static boolean forceZoomDivisors = false;
	private static Acknowledgement acknowledgement = Acknowledgement.NONE;
	private static double maximumZoomDivisor = 0.0D;
	private static double minimumZoomDivisor = 0.0D;
	private static boolean spyglassDependency = false;
	private static boolean spyglassOverlay = false;

	private static Text toastTitle = Text.translatable("toast.ok_zoomer.title");

	private static void sendToast(MinecraftClient client, Text description) {
		if (OkZoomerConfigManager.SHOW_RESTRICTION_TOASTS.value()) {
			client.getToastManager().add(SystemToast.create(client, SystemToast.Type.TUTORIAL_HINT, toastTitle, description));
		}
	}

	//Registers all the packets
	public static void registerPackets() {
		/*  The "Disable Zoom" packet,
			If this packet is received, Ok Zoomer's zoom will be disabled completely while in the server
			Supported since Ok Zoomer 4.0.0 (1.16)
			Arguments: None */
		ClientPlayNetworking.registerGlobalReceiver(DISABLE_ZOOM_PACKET_ID, (client, handler, buf, sender) -> {
			client.execute(() -> {
				ZoomUtils.LOGGER.info("[Ok Zoomer] This server has disabled zooming");
				disableZoom = true;
				ZoomPackets.checkRestrictions();
			});
		});

		/*  The "Disable Zoom Scrolling" packet,
			If this packet is received, zoom scrolling will be disabled while in the server
			Supported since Ok Zoomer 4.0.0 (1.16)
			Arguments: None */
		ClientPlayNetworking.registerGlobalReceiver(DISABLE_ZOOM_SCROLLING_PACKET_ID, (client, handler, buf, sender) -> {
			client.execute(() -> {
				ZoomUtils.LOGGER.info("[Ok Zoomer] This server has disabled zoom scrolling");
				ZoomPackets.applyDisableZoomScrolling();
				disableZoomScrolling = true;
				ZoomPackets.checkRestrictions();
			});
		});

		/*  The "Force Classic Mode" packet,
			If this packet is received, the Classic Mode will be activated while connected to the server,
			under the Classic mode, the Classic preset will be forced on all non-cosmetic options
			Supported since Ok Zoomer 5.0.0-beta.1 (1.17)
			Arguments: None */
		ClientPlayNetworking.registerGlobalReceiver(FORCE_CLASSIC_MODE_PACKET_ID, (client, handler, buf, sender) -> {
			client.execute(() -> {
				ZoomUtils.LOGGER.info("[Ok Zoomer] This server has imposed classic mode");
				disableZoomScrolling = true;
				forceClassicMode = true;
				ZoomPackets.applyDisableZoomScrolling();
				ZoomPackets.applyClassicMode();
				OkZoomerConfigManager.configureZoomInstance();
				ZoomPackets.checkRestrictions();
			});
		});

		/*  The "Force Zoom Divisor" packet,
			If this packet is received, the minimum and maximum zoom divisor values will be overriden
			with the provided arguments
			Supported since Ok Zoomer 5.0.0-beta.2 (1.17)
			Arguments: One double (max & min) or two doubles (first is max, second is min) */
		ClientPlayNetworking.registerGlobalReceiver(FORCE_ZOOM_DIVISOR_PACKET_ID, (client, handler, buf, sender) -> {
			int readableBytes = buf.readableBytes();
			if (readableBytes == 8 || readableBytes == 16) {
				double maxDouble = buf.readDouble();
				double minDouble = (readableBytes == 16) ? buf.readDouble() : maxDouble;
				client.execute(() -> {
					if ((minDouble <= 0.0 || maxDouble <= 0.0) || minDouble > maxDouble) {
						ZoomUtils.LOGGER.info(String.format("[Ok Zoomer] This server has attempted to set invalid divisor values! (min %s, max %s)", minDouble, maxDouble));
					} else {
						ZoomUtils.LOGGER.info(String.format("[Ok Zoomer] This server has set the zoom divisors to minimum %s and maximum %s", minDouble, maxDouble));
						maximumZoomDivisor = maxDouble;
						minimumZoomDivisor = minDouble;
						forceZoomDivisors = true;
						OkZoomerConfigManager.configureZoomInstance();
						ZoomPackets.checkRestrictions();
					}
				});
			}
		});

		/*  The "Acknowledge Mod" packet,
			If received, a toast will appear, the toast will either state that
			the server won't restrict the mod or say that the server controls will be activated
			Supported since Ok Zoomer 5.0.0-beta.2 (1.17)
			Arguments: one boolean, false for restricting, true for restrictionless */
		ClientPlayNetworking.registerGlobalReceiver(ACKNOWLEDGE_MOD_PACKET_ID, (client, handler, buf, sender) -> {
			boolean restricting = !buf.readBoolean();
			client.execute(() -> {
				checkRestrictions();
				if (restricting) {
					if (ZoomPackets.getAcknowledgement().equals(Acknowledgement.HAS_RESTRICTIONS)) {
						ZoomUtils.LOGGER.info("[Ok Zoomer] This server acknowledges the mod and has established some restrictions");
						ZoomPackets.sendToast(client, Text.translatable("toast.ok_zoomer.acknowledge_mod_restrictions"));
					}
				} else {
					if (ZoomPackets.getAcknowledgement().equals(Acknowledgement.HAS_NO_RESTRICTIONS)) {
						ZoomUtils.LOGGER.info("[Ok Zoomer] This server acknowledges the mod and establishes no restrictions");
						ZoomPackets.sendToast(client, Text.translatable("toast.ok_zoomer.acknowledge_mod"));
					}
				}
			});
		});

		/*  The "Force Spyglass" packet,
			This packet lets the server to impose a spyglass restriction
			Supported since Ok Zoomer 5.0.0-beta.4 (1.18.2)
			Arguments: probably some, we'll see */
		ClientPlayNetworking.registerGlobalReceiver(FORCE_SPYGLASS_PACKET_ID, (client, handler, buf, sender) -> {
			boolean requireItem = buf.readBoolean();
			boolean replaceZoom = buf.readBoolean();
			client.execute(() -> {
				ZoomUtils.LOGGER.info(String.format("[Ok Zoomer] This server has the following spyglass restrictions: Require Item: %s, Replace Zoom: %s", requireItem, replaceZoom));

				OkZoomerConfigManager.SPYGLASS_DEPENDENCY.setOverride(requireItem
					? (replaceZoom ? SpyglassDependency.BOTH : SpyglassDependency.REQUIRE_ITEM)
					: (replaceZoom ? SpyglassDependency.REPLACE_ZOOM : null));
				spyglassDependency = true;

				ZoomPackets.checkRestrictions();
			});
		});

		/*  The "Force Spyglass Overlay" packet,
			This packet will let the server restrict the mod to spyglass-only usage
			Not supported yet!
			Arguments: probably some, we'll see */
			ClientPlayNetworking.registerGlobalReceiver(FORCE_SPYGLASS_OVERLAY_PACKET_ID, (client, handler, buf, sender) -> {
				client.execute(() -> {
					ZoomUtils.LOGGER.info(String.format("[Ok Zoomer] This server has imposed a spyglass overlay on the zoom"));
					OkZoomerConfigManager.ZOOM_OVERLAY.setOverride(ZoomOverlays.SPYGLASS);
					spyglassOverlay = true;
					ZoomPackets.checkRestrictions();
				});
			});

		/*
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			PacketByteBuf emptyBuf = PacketByteBufs.empty();
			//sender.sendPacket(DISABLE_ZOOM_PACKET_ID, emptyBuf);
			//sender.sendPacket(DISABLE_ZOOM_SCROLLING_PACKET_ID, emptyBuf);
			//sender.sendPacket(FORCE_CLASSIC_MODE_PACKET_ID, emptyBuf);
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeDouble(25.0D);
			buf.writeDouble(1.0D);
			sender.sendPacket(FORCE_ZOOM_DIVISOR_PACKET_ID, buf);
			PacketByteBuf buffy = PacketByteBufs.create();
			buffy.writeBoolean(true);
			buffy.writeBoolean(true);
			sender.sendPacket(FORCE_SPYGLASS_PACKET_ID, buffy);
			sender.sendPacket(FORCE_SPYGLASS_OVERLAY_PACKET_ID, emptyBuf);
			PacketByteBuf boolBuf = PacketByteBufs.create();
			boolBuf.writeBoolean(false);
			sender.sendPacket(ACKNOWLEDGE_MOD_PACKET_ID, boolBuf);
		});
		*/

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (ZoomPackets.hasRestrictions) {
				ZoomPackets.resetPacketSignals();
			}
		});
	}

	public static boolean getHasRestrictions() {
		return hasRestrictions;
	}

	private static void checkRestrictions() {
		boolean hasRestrictions = disableZoom
			|| disableZoomScrolling
			|| forceClassicMode
			|| forceZoomDivisors
			|| spyglassDependency
			|| spyglassOverlay;

		ZoomPackets.hasRestrictions = hasRestrictions;
		if (hasRestrictions) {
			ZoomPackets.acknowledgement = Acknowledgement.HAS_RESTRICTIONS;
		} else {
			ZoomPackets.acknowledgement = Acknowledgement.HAS_NO_RESTRICTIONS;
		}
	}

	public static boolean getDisableZoom() {
		return disableZoom;
	}

	public static boolean getDisableZoomScrolling() {
		return disableZoomScrolling;
	}

	public static boolean getForceClassicMode() {
		return forceClassicMode;
	}

	public static boolean getForceZoomDivisors() {
		return forceZoomDivisors;
	}

	public static Acknowledgement getAcknowledgement() {
		return acknowledgement;
	}

	public static double getMaximumZoomDivisor() {
		return maximumZoomDivisor;
	}

	public static double getMinimumZoomDivisor() {
		return minimumZoomDivisor;
	}

	public static boolean getSpyglassDependency() {
		return spyglassDependency;
	}

	public static boolean getSpyglassOverlay() {
		return spyglassOverlay;
	}

	private static void applyDisableZoomScrolling() {
		OkZoomerConfigManager.ZOOM_SCROLLING.setOverride(false);
		OkZoomerConfigManager.EXTRA_KEY_BINDS.setOverride(false);
	}

	private static void applyClassicMode() {
		OkZoomerConfigManager.CINEMATIC_CAMERA.setOverride(CinematicCameraOptions.VANILLA);
		OkZoomerConfigManager.REDUCE_SENSITIVITY.setOverride(false);
		OkZoomerConfigManager.ZOOM_DIVISOR.setOverride(4.0D);
	}

	//The method used to reset the signals once left the server.
	private static void resetPacketSignals() {
		ZoomPackets.hasRestrictions = false;
		ZoomPackets.disableZoom = false;
		ZoomPackets.disableZoomScrolling = false;
		OkZoomerConfigManager.ZOOM_SCROLLING.removeOverride();
		OkZoomerConfigManager.EXTRA_KEY_BINDS.removeOverride();
		ZoomPackets.forceClassicMode = false;
		OkZoomerConfigManager.CINEMATIC_CAMERA.removeOverride();
		OkZoomerConfigManager.REDUCE_SENSITIVITY.removeOverride();
		OkZoomerConfigManager.ZOOM_DIVISOR.removeOverride();
		ZoomPackets.forceZoomDivisors = false;
		ZoomPackets.maximumZoomDivisor = 0.0D;
		ZoomPackets.minimumZoomDivisor = 0.0D;
		ZoomPackets.acknowledgement = Acknowledgement.NONE;
		ZoomPackets.spyglassDependency = false;
		OkZoomerConfigManager.SPYGLASS_DEPENDENCY.removeOverride();
		ZoomPackets.spyglassOverlay = false;
		OkZoomerConfigManager.ZOOM_OVERLAY.removeOverride();
	}
}
