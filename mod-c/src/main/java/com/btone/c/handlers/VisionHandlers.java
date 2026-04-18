package com.btone.c.handlers;

import com.btone.c.rpc.RpcRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Vision handlers — `world.screenshot` and `world.screenshot_panorama`.
 *
 * <p>All framebuffer access must run on the render thread. Each capture
 * submits a runnable via {@link MinecraftClient#execute(Runnable)} that
 * fills a {@link CompletableFuture}, then the RPC handler awaits.
 *
 * <p>The screenshot pipeline:
 * <ol>
 *   <li>Optionally override player yaw/pitch (camera follows player rotation
 *       at the next render frame).</li>
 *   <li>Read framebuffer via {@link ScreenshotRecorder#takeScreenshot} —
 *       callback API that hands us a {@link NativeImage}.</li>
 *   <li>Convert NativeImage ARGB pixels to a Java {@link BufferedImage}.</li>
 *   <li>Optional bilinear downscale to requested {@code width}.</li>
 *   <li>Encode to PNG/JPEG bytes via {@link ImageIO}, base64 result.</li>
 *   <li>Compute camera matrices and project entities/blocks/crosshair to
 *       screen using THE OVERRIDE camera so annotations match the image.</li>
 *   <li>Restore the saved yaw/pitch.</li>
 * </ol>
 */
public final class VisionHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final long TIMEOUT_MS = 5_000;
    private static final long PANORAMA_FRAME_MS = 3_000;
    private static final int MAX_ENTITIES = 64;
    private static final int MAX_BLOCKS = 128;
    private static final double ENTITY_RANGE = 64.0;

    private VisionHandlers() {}

    /** Identifiers of blocks worth annotating in the structured side-channel. */
    private static final Set<Identifier> INTERACTIVE_BLOCKS;
    static {
        Set<Identifier> s = new java.util.HashSet<>();
        String[] explicit = {
                "minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
                "minecraft:barrel", "minecraft:furnace", "minecraft:blast_furnace",
                "minecraft:smoker", "minecraft:crafting_table", "minecraft:brewing_stand",
                "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
                "minecraft:beacon", "minecraft:hopper", "minecraft:dropper",
                "minecraft:dispenser", "minecraft:lectern", "minecraft:cartography_table",
                "minecraft:smithing_table", "minecraft:stonecutter", "minecraft:grindstone",
                "minecraft:loom", "minecraft:fletching_table", "minecraft:composter",
                "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:lava_cauldron",
                "minecraft:powder_snow_cauldron", "minecraft:jukebox", "minecraft:note_block",
                "minecraft:repeater", "minecraft:comparator", "minecraft:daylight_detector",
                "minecraft:lever",
        };
        for (String id : explicit) s.add(Identifier.of(id));
        // Color- and wood-suffix families — just iterate the registry once at
        // class load (Registries.BLOCK is populated by the time any handler
        // dispatches; class init is lazy).
        String[] suffixes = {"_bed", "_door", "_trapdoor", "_button", "_pressure_plate",
                "_sign", "_hanging_sign", "_wall_sign", "_wall_hanging_sign"};
        for (Identifier id : Registries.BLOCK.getIds()) {
            String path = id.getPath();
            for (String suf : suffixes) {
                if (path.endsWith(suf)) { s.add(id); break; }
            }
        }
        INTERACTIVE_BLOCKS = java.util.Collections.unmodifiableSet(s);
    }

    public static void registerAll(RpcRouter r) {
        r.register("world.screenshot", params -> {
            CaptureRequest req = CaptureRequest.of(params);
            CompletableFuture<ObjectNode> fut = new CompletableFuture<>();
            MinecraftClient.getInstance().execute(() -> {
                try { fut.complete(captureSingle(req)); }
                catch (Throwable t) { fut.completeExceptionally(t); }
            });
            return await(fut, TIMEOUT_MS);
        });
        r.register("world.screenshot_panorama", params -> {
            CaptureRequest base = CaptureRequest.of(params);
            int angles = clamp(params.path("angles").asInt(4), 1, 16);
            float[][] offsets = panoramaOffsets(angles);
            ObjectNode root = M.createObjectNode();
            ArrayNode frames = root.putArray("frames");
            for (float[] off : offsets) {
                CaptureRequest each = base.withYawPitchOffset(off[0], off[1]);
                CompletableFuture<ObjectNode> fut = new CompletableFuture<>();
                MinecraftClient.getInstance().execute(() -> {
                    try { fut.complete(captureSingle(each)); }
                    catch (Throwable t) { fut.completeExceptionally(t); }
                });
                frames.add(await(fut, PANORAMA_FRAME_MS));
            }
            return root;
        });
    }

    // --- Request struct -----------------------------------------------------

    private static final class CaptureRequest {
        Integer width;
        Float yaw;
        Float pitch;
        boolean includeHud;
        int annotateRange;
        String format; // "png" | "jpeg"

        static CaptureRequest of(JsonNode p) {
            CaptureRequest r = new CaptureRequest();
            if (p == null || p.isMissingNode()) p = M.createObjectNode();
            r.width = p.has("width") ? p.get("width").asInt() : null;
            r.yaw = p.has("yaw") ? (float) p.get("yaw").asDouble() : null;
            r.pitch = p.has("pitch") ? (float) p.get("pitch").asDouble() : null;
            r.includeHud = p.path("includeHud").asBoolean(false);
            r.annotateRange = clamp(p.path("annotateRange").asInt(16), 1, 64);
            r.format = p.path("format").asText("png").toLowerCase();
            if (!r.format.equals("png") && !r.format.equals("jpeg")) r.format = "png";
            return r;
        }

        CaptureRequest withYawPitchOffset(float yawOff, float pitchOverride) {
            CaptureRequest c = new CaptureRequest();
            c.width = this.width;
            c.includeHud = this.includeHud;
            c.annotateRange = this.annotateRange;
            c.format = this.format;
            PlayerEntity p = MinecraftClient.getInstance().player;
            float baseYaw = (this.yaw != null) ? this.yaw : (p != null ? p.getYaw() : 0f);
            float basePitch = (this.pitch != null) ? this.pitch : (p != null ? p.getPitch() : 0f);
            c.yaw = baseYaw + yawOff;
            c.pitch = Float.isNaN(pitchOverride) ? basePitch : pitchOverride;
            return c;
        }
    }

    // --- Single-shot capture ------------------------------------------------

    private static ObjectNode captureSingle(CaptureRequest req) throws Exception {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) throw new IllegalStateException("no_world");
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null) throw new IllegalStateException("no_framebuffer");

        // Save & override player rotation. Camera updates from player yaw/pitch
        // every render frame, so by the time takeScreenshot's callback fires
        // (same frame, same call stack) we still have the OLD camera matrices —
        // we therefore build the projection from the OVERRIDE values directly.
        float savedYaw = mc.player.getYaw();
        float savedPitch = mc.player.getPitch();
        boolean savedHud = mc.options.hudHidden;
        boolean override = (req.yaw != null) || (req.pitch != null);
        float useYaw = (req.yaw != null) ? req.yaw : savedYaw;
        float usePitch = (req.pitch != null) ? req.pitch : savedPitch;

        try {
            if (override) {
                mc.player.setYaw(useYaw);
                mc.player.setPitch(usePitch);
                mc.player.setHeadYaw(useYaw);
                mc.player.setBodyYaw(useYaw);
                // Re-run the camera update so framebuffer matches the new rotation.
                if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
                    mc.gameRenderer.getCamera().update(mc.world, mc.player, false, false, 1.0f);
                }
            }
            mc.options.hudHidden = !req.includeHud;

            // Capture pixels. takeScreenshot is callback-style; the callback
            // runs synchronously inside copyTextureToBuffer's completion.
            NativeImage[] holder = new NativeImage[1];
            ScreenshotRecorder.takeScreenshot(fb, img -> holder[0] = img);
            if (holder[0] == null) throw new IllegalStateException("framebuffer_capture_failed");

            int srcW, srcH;
            BufferedImage buf;
            try (NativeImage ni = holder[0]) {
                srcW = ni.getWidth();
                srcH = ni.getHeight();
                buf = nativeToBuffered(ni);
            }

            int outW = (req.width != null && req.width > 0)
                    ? Math.min(req.width, srcW)
                    : srcW;
            int outH = (int) Math.round((double) outW * srcH / srcW);
            BufferedImage finalImg = (outW == srcW && outH == srcH)
                    ? buf : downscale(buf, outW, outH);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(finalImg, req.format.equals("jpeg") ? "jpeg" : "png", baos);
            String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

            // Build response.
            ObjectNode out = M.createObjectNode();
            out.put("image", base64);
            out.put("format", req.format);
            out.put("width", outW);
            out.put("height", outH);
            out.put("captured_at", System.currentTimeMillis());
            Camera cam = mc.gameRenderer.getCamera();
            ObjectNode camNode = out.putObject("camera");
            camNode.put("yaw", useYaw);
            camNode.put("pitch", usePitch);
            ObjectNode camPos = camNode.putObject("pos");
            Vec3d cp = cam.getPos();
            camPos.put("x", cp.x); camPos.put("y", cp.y); camPos.put("z", cp.z);

            // Annotations using the OVERRIDE camera matrices.
            float fov = mc.options.getFov().getValue().floatValue();
            Matrix4f projection = mc.gameRenderer.getBasicProjectionMatrix(fov);
            // View = inverse-rotation around camera.
            Quaternionf invRot = cam.getRotation().conjugate(new Quaternionf());
            Matrix4f view = new Matrix4f().rotation(invRot);
            ObjectNode anns = out.putObject("annotations");
            anns.set("entities", entityAnnotations(mc, cam, view, projection, outW, outH));
            anns.set("blocks", blockAnnotations(mc, cam, view, projection, outW, outH, req.annotateRange));
            anns.set("lookingAt", crosshairAnnotation(mc));

            return out;
        } finally {
            mc.options.hudHidden = savedHud;
            if (override && mc.player != null) {
                mc.player.setYaw(savedYaw);
                mc.player.setPitch(savedPitch);
                mc.player.setHeadYaw(savedYaw);
                mc.player.setBodyYaw(savedYaw);
            }
        }
    }

    // --- Image helpers ------------------------------------------------------

    private static BufferedImage nativeToBuffered(NativeImage ni) {
        int w = ni.getWidth(), h = ni.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // copyPixelsArgb returns a flat array with origin top-left in the
        // NativeImage's coordinate system, which matches our needs.
        int[] pixels = ni.copyPixelsArgb();
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    private static BufferedImage downscale(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    // --- Projection ---------------------------------------------------------

    /** Returns (screenX, screenY) or null if the world point is offscreen / behind. */
    private static float[] project(Vec3d worldPos, Camera cam, Matrix4f view, Matrix4f proj,
                                   int imageW, int imageH) {
        Vec3d cp = cam.getPos();
        Vector4f v = new Vector4f(
                (float) (worldPos.x - cp.x),
                (float) (worldPos.y - cp.y),
                (float) (worldPos.z - cp.z),
                1.0f);
        view.transform(v);
        proj.transform(v);
        if (v.w <= 0.0001f) return null;
        float ndcX = v.x / v.w;
        float ndcY = v.y / v.w;
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return null;
        float sx = (ndcX * 0.5f + 0.5f) * imageW;
        float sy = (1f - (ndcY * 0.5f + 0.5f)) * imageH;
        return new float[]{sx, sy};
    }

    // --- Annotation builders ------------------------------------------------

    private static ArrayNode entityAnnotations(MinecraftClient mc, Camera cam, Matrix4f view,
                                               Matrix4f proj, int w, int h) {
        ArrayNode arr = M.createArrayNode();
        if (mc.world == null) return arr;
        Vec3d cp = cam.getPos();
        record Hit(Entity e, float[] center, float minX, float minY, float maxX, float maxY, double dist) {}
        List<Hit> hits = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == null || e == mc.player || !e.isAlive() || e.isRemoved()) continue;
            double dist = e.getPos().distanceTo(cp);
            if (dist > ENTITY_RANGE) continue;
            Vec3d center = e.getPos().add(0, e.getBoundingBox().getLengthY() * 0.5, 0);
            float[] c = project(center, cam, view, proj, w, h);
            if (c == null) continue;
            // Project all 8 bbox corners to compute screen AABB.
            Box bb = e.getBoundingBox();
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            int seen = 0;
            for (int i = 0; i < 8; i++) {
                double cx = ((i & 1) == 0) ? bb.minX : bb.maxX;
                double cy = ((i & 2) == 0) ? bb.minY : bb.maxY;
                double cz = ((i & 4) == 0) ? bb.minZ : bb.maxZ;
                float[] p = project(new Vec3d(cx, cy, cz), cam, view, proj, w, h);
                if (p == null) continue;
                seen++;
                if (p[0] < minX) minX = p[0];
                if (p[1] < minY) minY = p[1];
                if (p[0] > maxX) maxX = p[0];
                if (p[1] > maxY) maxY = p[1];
            }
            if (seen == 0) {
                // Fallback to a 1px box at the center if no corner was visible
                // but the center projected.
                minX = c[0]; maxX = c[0]; minY = c[1]; maxY = c[1];
            }
            hits.add(new Hit(e, c, minX, minY, maxX, maxY, dist));
        }
        hits.sort(Comparator.comparingDouble(Hit::dist));
        int n = Math.min(hits.size(), MAX_ENTITIES);
        for (int i = 0; i < n; i++) {
            Hit hit = hits.get(i);
            ObjectNode o = arr.addObject();
            o.put("entityId", hit.e.getId());
            o.put("type", Registries.ENTITY_TYPE.getId(hit.e.getType()).toString());
            o.put("name", hit.e.getName().getString());
            o.put("distance", hit.dist);
            ObjectNode screen = o.putObject("screen");
            screen.put("x", hit.minX);
            screen.put("y", hit.minY);
            screen.put("w", hit.maxX - hit.minX);
            screen.put("h", hit.maxY - hit.minY);
            ObjectNode world = o.putObject("world");
            Vec3d ep = hit.e.getPos();
            world.put("x", ep.x); world.put("y", ep.y); world.put("z", ep.z);
        }
        return arr;
    }

    private static ArrayNode blockAnnotations(MinecraftClient mc, Camera cam, Matrix4f view,
                                              Matrix4f proj, int w, int h, int range) {
        ArrayNode arr = M.createArrayNode();
        if (mc.world == null || mc.player == null) return arr;
        Vec3d cp = cam.getPos();
        BlockPos origin = mc.player.getBlockPos();
        record Hit(Identifier id, BlockPos pos, float[] screen, double dist) {}
        List<Hit> hits = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos bp = origin.add(dx, dy, dz);
                    var state = mc.world.getBlockState(bp);
                    if (state.isAir()) continue;
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    if (!INTERACTIVE_BLOCKS.contains(id)) continue;
                    Vec3d center = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
                    float[] s = project(center, cam, view, proj, w, h);
                    if (s == null) continue;
                    double dist = center.distanceTo(cp);
                    hits.add(new Hit(id, bp, s, dist));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::dist));
        int n = Math.min(hits.size(), MAX_BLOCKS);
        for (int i = 0; i < n; i++) {
            Hit hit = hits.get(i);
            ObjectNode o = arr.addObject();
            o.put("id", hit.id.toString());
            o.put("distance", hit.dist);
            ObjectNode screen = o.putObject("screen");
            screen.put("x", hit.screen[0]);
            screen.put("y", hit.screen[1]);
            ObjectNode world = o.putObject("world");
            world.put("x", hit.pos.getX());
            world.put("y", hit.pos.getY());
            world.put("z", hit.pos.getZ());
        }
        return arr;
    }

    private static ObjectNode crosshairAnnotation(MinecraftClient mc) {
        ObjectNode n = M.createObjectNode();
        HitResult hit = mc.crosshairTarget;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            n.put("kind", "miss");
            return n;
        }
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK && mc.world != null) {
            n.put("kind", "block");
            BlockPos bp = bhr.getBlockPos();
            n.put("id", Registries.BLOCK.getId(mc.world.getBlockState(bp).getBlock()).toString());
            n.put("side", bhr.getSide().asString());
            ObjectNode w = n.putObject("world");
            w.put("x", bp.getX()); w.put("y", bp.getY()); w.put("z", bp.getZ());
            ObjectNode hp = n.putObject("hit");
            Vec3d hv = bhr.getPos();
            hp.put("x", hv.x); hp.put("y", hv.y); hp.put("z", hv.z);
            return n;
        }
        if (hit instanceof EntityHitResult ehr) {
            n.put("kind", "entity");
            Entity e = ehr.getEntity();
            n.put("entityId", e.getId());
            n.put("type", Registries.ENTITY_TYPE.getId(e.getType()).toString());
            ObjectNode w = n.putObject("world");
            Vec3d ep = e.getPos();
            w.put("x", ep.x); w.put("y", ep.y); w.put("z", ep.z);
            return n;
        }
        n.put("kind", "miss");
        return n;
    }

    // --- Panorama -----------------------------------------------------------

    private static float[][] panoramaOffsets(int angles) {
        float NAN = Float.NaN; // sentinel: keep base pitch
        return switch (angles) {
            case 4 -> new float[][]{
                    {0,   NAN}, {90,  NAN}, {180, NAN}, {270, NAN}
            };
            case 6 -> new float[][]{
                    {0,   NAN}, {90,  NAN}, {180, NAN}, {270, NAN},
                    {0,  -90f}, {0,    90f}
            };
            case 8 -> new float[][]{
                    {0,   NAN}, {45,  NAN}, {90,  NAN}, {135, NAN},
                    {180, NAN}, {225, NAN}, {270, NAN}, {315, NAN}
            };
            default -> {
                float step = 360f / angles;
                float[][] out = new float[angles][2];
                for (int i = 0; i < angles; i++) { out[i][0] = i * step; out[i][1] = NAN; }
                yield out;
            }
        };
    }

    // --- Misc ---------------------------------------------------------------

    private static ObjectNode await(CompletableFuture<ObjectNode> fut, long timeoutMs) throws Exception {
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new RuntimeException("capture_timeout");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c != null ? c : ee);
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
