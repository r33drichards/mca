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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Vision handlers — {@code world.screenshot} and {@code world.screenshot_panorama}.
 *
 * <h2>Threading model</h2>
 *
 * <p>The naive layout — {@code mc.execute(() -> { takeScreenshot(...); future.get(); })} —
 * deadlocks. {@link ScreenshotRecorder#takeScreenshot} schedules an async GPU
 * readback whose completion callback ALSO runs on the render thread. If the
 * outer runnable blocks waiting for the inner future on that same thread, the
 * callback can never fire.
 *
 * <p>The fix: never block on the render thread. The pipeline is split:
 *
 * <ol>
 *   <li>The RPC handler (running on the HTTP server thread, off the render
 *       thread) creates a {@code respFuture} and submits a runnable via
 *       {@link MinecraftClient#execute(Runnable)}.</li>
 *   <li>That runnable saves player rotation/HUD, applies the override,
 *       computes camera matrices, and calls
 *       {@code ScreenshotRecorder.takeScreenshot(fb, callback)}. It returns
 *       immediately.</li>
 *   <li>The callback (later, on the render thread, possibly next render tick)
 *       converts the {@link NativeImage} → {@link BufferedImage} → PNG/JPEG
 *       bytes → base64, builds entity/block/crosshair annotations using the
 *       OVERRIDE camera matrices that were captured into final-locals BEFORE
 *       takeScreenshot was called, restores rotation/HUD, and completes
 *       {@code respFuture}.</li>
 *   <li>The HTTP thread blocks on {@code respFuture.get(5s)}. This is the
 *       only blocking call and it is NOT on the render thread, so the
 *       callback can drain it.</li>
 * </ol>
 *
 * <p>Panorama chains N captures sequentially via
 * {@link CompletableFuture#thenCompose}; only the final aggregated future is
 * awaited on the HTTP thread.
 */
public final class VisionHandlers {
    private static final ObjectMapper M = new ObjectMapper();
    private static final long TIMEOUT_MS = 5_000;
    private static final long PANORAMA_TIMEOUT_MS = 30_000;
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
        // Color- and wood-suffix families — iterate the registry once at class
        // load. Registries.BLOCK is populated long before any handler dispatch.
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
            CompletableFuture<ObjectNode> fut = scheduleCapture(req);
            return await(fut, TIMEOUT_MS, "capture_timeout");
        });
        r.register("world.screenshot_panorama", params -> {
            CaptureRequest base = CaptureRequest.of(params);
            int angles = clamp(params.path("angles").asInt(4), 1, 16);
            float[][] offsets = panoramaOffsets(angles);

            // Resolve base yaw/pitch on the render thread so each frame's
            // override is computed from a consistent snapshot. We do this via
            // a small future (no GPU work, so no deadlock risk).
            CompletableFuture<float[]> seedFut = new CompletableFuture<>();
            MinecraftClient.getInstance().execute(() -> {
                PlayerEntity p = MinecraftClient.getInstance().player;
                float by = (base.yaw != null) ? base.yaw : (p != null ? p.getYaw() : 0f);
                float bp = (base.pitch != null) ? base.pitch : (p != null ? p.getPitch() : 0f);
                seedFut.complete(new float[]{by, bp});
            });
            float[] seed = await0(seedFut, 1_000, "panorama_seed_timeout");

            // Chain N single-shot captures sequentially.
            CompletableFuture<ArrayNode> chain = CompletableFuture.completedFuture(M.createArrayNode());
            for (float[] off : offsets) {
                final float yawAbs = seed[0] + off[0];
                final float pitchAbs = Float.isNaN(off[1]) ? seed[1] : off[1];
                chain = chain.thenCompose(arr -> {
                    CaptureRequest each = base.withAbsolute(yawAbs, pitchAbs);
                    return scheduleCapture(each).thenApply(frame -> {
                        // Stamp yaw/pitch onto the frame for caller convenience.
                        frame.put("yaw", yawAbs);
                        frame.put("pitch", pitchAbs);
                        arr.add(frame);
                        return arr;
                    });
                });
            }
            ArrayNode frames = await0(chain, PANORAMA_TIMEOUT_MS, "panorama_timeout");
            ObjectNode root = M.createObjectNode();
            root.set("frames", frames);
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

        CaptureRequest withAbsolute(float yawAbs, float pitchAbs) {
            CaptureRequest c = new CaptureRequest();
            c.width = this.width;
            c.includeHud = this.includeHud;
            c.annotateRange = this.annotateRange;
            c.format = this.format;
            c.yaw = yawAbs;
            c.pitch = pitchAbs;
            return c;
        }
    }

    // --- Async capture pipeline --------------------------------------------

    /**
     * Submits one capture to the render thread. The returned future completes
     * (off any thread guarantee — typically the render thread inside the
     * GPU-readback callback) with a fully-populated response node.
     *
     * <p>Never blocks the caller. Never blocks the render thread.
     */
    private static CompletableFuture<ObjectNode> scheduleCapture(CaptureRequest req) {
        CompletableFuture<ObjectNode> respFuture = new CompletableFuture<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            try {
                if (mc.world == null || mc.player == null) {
                    respFuture.completeExceptionally(new IllegalStateException("no_world"));
                    return;
                }
                Framebuffer fb = mc.getFramebuffer();
                if (fb == null) {
                    respFuture.completeExceptionally(new IllegalStateException("no_framebuffer"));
                    return;
                }

                // Snapshot original state for restoration.
                final float savedYaw = mc.player.getYaw();
                final float savedPitch = mc.player.getPitch();
                final float savedHeadYaw = mc.player.getHeadYaw();
                final float savedBodyYaw = mc.player.getBodyYaw();
                final boolean savedHud = mc.options.hudHidden;
                final boolean override = (req.yaw != null) || (req.pitch != null);
                final float useYaw = (req.yaw != null) ? req.yaw : savedYaw;
                final float usePitch = (req.pitch != null) ? req.pitch : savedPitch;

                if (override) {
                    mc.player.setYaw(useYaw);
                    mc.player.setPitch(usePitch);
                    mc.player.setHeadYaw(useYaw);
                    mc.player.setBodyYaw(useYaw);
                    if (mc.gameRenderer != null && mc.gameRenderer.getCamera() != null) {
                        mc.gameRenderer.getCamera().update(mc.world, mc.player, false, false, 1.0f);
                    }
                }
                mc.options.hudHidden = !req.includeHud;

                // Snapshot camera + projection NOW (override has been applied).
                // The GPU readback callback may fire on a later tick, by which
                // point we'll have restored rotation — so we must lock these in.
                final Camera cam = mc.gameRenderer.getCamera();
                final Vec3d camPosSnapshot = cam.getPos();
                final Quaternionf invRot = cam.getRotation().conjugate(new Quaternionf());
                final Matrix4f viewSnapshot = new Matrix4f().rotation(invRot);
                final float fov = mc.options.getFov().getValue().floatValue();
                final Matrix4f projSnapshot = mc.gameRenderer.getBasicProjectionMatrix(fov);

                // Build the annotations NOW too, before we restore state and
                // before the GPU callback fires. The world snapshot at this
                // point matches what's about to be captured (the render
                // pipeline is already in flight for the current frame).
                final ArrayNode entityAnns =
                        entityAnnotations(mc, camPosSnapshot, viewSnapshot, projSnapshot);
                final ArrayNode blockAnns =
                        blockAnnotations(mc, camPosSnapshot, viewSnapshot, projSnapshot, req.annotateRange);
                final ObjectNode crossAnn = crosshairAnnotation(mc);

                // Schedule the GPU readback. The callback may fire NOW or a
                // tick later — either way it runs on the render thread.
                ScreenshotRecorder.takeScreenshot(fb, img -> {
                    try {
                        if (img == null) {
                            respFuture.completeExceptionally(
                                    new IllegalStateException("null_native_image"));
                            return;
                        }
                        ObjectNode out = encodeAndAssemble(
                                req, img, useYaw, usePitch, camPosSnapshot,
                                entityAnns, blockAnns, crossAnn);
                        respFuture.complete(out);
                    } catch (Throwable t) {
                        respFuture.completeExceptionally(t);
                    }
                });

                // Restore state immediately. The framebuffer the GPU is
                // reading from is already locked; subsequent player updates
                // do not affect the in-flight capture.
                mc.options.hudHidden = savedHud;
                if (override) {
                    mc.player.setYaw(savedYaw);
                    mc.player.setPitch(savedPitch);
                    mc.player.setHeadYaw(savedHeadYaw);
                    mc.player.setBodyYaw(savedBodyYaw);
                }
            } catch (Throwable t) {
                respFuture.completeExceptionally(t);
            }
        });
        return respFuture;
    }

    /** Image conversion + base64 + envelope. Runs inside the GPU-readback callback. */
    private static ObjectNode encodeAndAssemble(CaptureRequest req,
                                                NativeImage img,
                                                float useYaw,
                                                float usePitch,
                                                Vec3d camPos,
                                                ArrayNode entityAnns,
                                                ArrayNode blockAnns,
                                                ObjectNode crossAnn) throws Exception {
        int srcW, srcH;
        BufferedImage buf;
        try (NativeImage ni = img) {
            srcW = ni.getWidth();
            srcH = ni.getHeight();
            buf = nativeToBuffered(ni);
        }

        int outW = (req.width != null && req.width > 0) ? Math.min(req.width, srcW) : srcW;
        int outH = (int) Math.round((double) outW * srcH / srcW);
        BufferedImage finalImg = (outW == srcW && outH == srcH) ? buf : downscale(buf, outW, outH);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(finalImg, req.format.equals("jpeg") ? "jpeg" : "png", baos);
        String base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());

        ObjectNode out = M.createObjectNode();
        out.put("image", base64);
        out.put("format", req.format);
        out.put("width", outW);
        out.put("height", outH);
        out.put("captured_at", System.currentTimeMillis());
        ObjectNode camNode = out.putObject("camera");
        camNode.put("yaw", useYaw);
        camNode.put("pitch", usePitch);
        ObjectNode camPosNode = camNode.putObject("pos");
        camPosNode.put("x", camPos.x);
        camPosNode.put("y", camPos.y);
        camPosNode.put("z", camPos.z);

        // Annotations were computed in normalized [0..1] coords (origin
        // top-left). Now that we know the final output image dims, scale to
        // pixel space so the agent gets concrete x/y/w/h matching .image.
        ObjectNode anns = out.putObject("annotations");
        anns.set("entities", scaleEntityAnns(entityAnns, outW, outH));
        anns.set("blocks", scaleBlockAnns(blockAnns, outW, outH));
        anns.set("lookingAt", crossAnn);
        return out;
    }

    private static ArrayNode scaleEntityAnns(ArrayNode src, int w, int h) {
        ArrayNode out = M.createArrayNode();
        for (JsonNode n : src) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            ObjectNode s = (ObjectNode) o.get("screen");
            double x = s.get("x").asDouble() * w;
            double y = s.get("y").asDouble() * h;
            double sw = s.get("w").asDouble() * w;
            double sh = s.get("h").asDouble() * h;
            s.put("x", x); s.put("y", y); s.put("w", sw); s.put("h", sh);
            s.remove("normalized");
            out.add(o);
        }
        return out;
    }

    private static ArrayNode scaleBlockAnns(ArrayNode src, int w, int h) {
        ArrayNode out = M.createArrayNode();
        for (JsonNode n : src) {
            ObjectNode o = (ObjectNode) n.deepCopy();
            ObjectNode s = (ObjectNode) o.get("screen");
            double x = s.get("x").asDouble() * w;
            double y = s.get("y").asDouble() * h;
            s.put("x", x); s.put("y", y);
            s.remove("normalized");
            out.add(o);
        }
        return out;
    }

    // --- Image helpers ------------------------------------------------------

    private static BufferedImage nativeToBuffered(NativeImage ni) {
        int w = ni.getWidth(), h = ni.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
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

    /**
     * Project a world point into normalized [0..1] screen coordinates (origin
     * top-left). Returns {@code null} if behind camera or off-screen. The
     * caller multiplies by whatever pixel resolution it eventually emits.
     */
    private static float[] projectNorm(Vec3d worldPos, Vec3d camPos, Matrix4f view, Matrix4f proj) {
        Vector4f v = new Vector4f(
                (float) (worldPos.x - camPos.x),
                (float) (worldPos.y - camPos.y),
                (float) (worldPos.z - camPos.z),
                1.0f);
        view.transform(v);
        proj.transform(v);
        if (v.w <= 0.0001f) return null;
        float ndcX = v.x / v.w;
        float ndcY = v.y / v.w;
        if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) return null;
        float u = ndcX * 0.5f + 0.5f;
        float vY = 1f - (ndcY * 0.5f + 0.5f);
        return new float[]{u, vY};
    }

    // --- Annotation builders (project to NORMALIZED [0..1] coords) ---------

    private static ArrayNode entityAnnotations(MinecraftClient mc, Vec3d camPos,
                                               Matrix4f view, Matrix4f proj) {
        ArrayNode arr = M.createArrayNode();
        if (mc.world == null) return arr;
        record Hit(Entity e, float[] center, float minU, float minV, float maxU, float maxV, double dist) {}
        List<Hit> hits = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == null || e == mc.player || !e.isAlive() || e.isRemoved()) continue;
            double dist = e.getPos().distanceTo(camPos);
            if (dist > ENTITY_RANGE) continue;
            Vec3d center = e.getPos().add(0, e.getBoundingBox().getLengthY() * 0.5, 0);
            float[] c = projectNorm(center, camPos, view, proj);
            if (c == null) continue;
            Box bb = e.getBoundingBox();
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            int seen = 0;
            for (int i = 0; i < 8; i++) {
                double cx = ((i & 1) == 0) ? bb.minX : bb.maxX;
                double cy = ((i & 2) == 0) ? bb.minY : bb.maxY;
                double cz = ((i & 4) == 0) ? bb.minZ : bb.maxZ;
                float[] p = projectNorm(new Vec3d(cx, cy, cz), camPos, view, proj);
                if (p == null) continue;
                seen++;
                if (p[0] < minU) minU = p[0];
                if (p[1] < minV) minV = p[1];
                if (p[0] > maxU) maxU = p[0];
                if (p[1] > maxV) maxV = p[1];
            }
            if (seen == 0) {
                minU = c[0]; maxU = c[0]; minV = c[1]; maxV = c[1];
            }
            hits.add(new Hit(e, c, minU, minV, maxU, maxV, dist));
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
            // Normalized [0..1] coords. Caller multiplies by image width/height.
            screen.put("x", hit.minU);
            screen.put("y", hit.minV);
            screen.put("w", hit.maxU - hit.minU);
            screen.put("h", hit.maxV - hit.minV);
            screen.put("normalized", true);
            ObjectNode world = o.putObject("world");
            Vec3d ep = hit.e.getPos();
            world.put("x", ep.x); world.put("y", ep.y); world.put("z", ep.z);
        }
        return arr;
    }

    private static ArrayNode blockAnnotations(MinecraftClient mc, Vec3d camPos, Matrix4f view,
                                              Matrix4f proj, int range) {
        ArrayNode arr = M.createArrayNode();
        if (mc.world == null || mc.player == null) return arr;
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
                    float[] s = projectNorm(center, camPos, view, proj);
                    if (s == null) continue;
                    double dist = center.distanceTo(camPos);
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
            screen.put("normalized", true);
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

    private static ObjectNode await(CompletableFuture<ObjectNode> fut, long timeoutMs, String onTimeout) throws Exception {
        return await0(fut, timeoutMs, onTimeout);
    }

    private static <T> T await0(CompletableFuture<T> fut, long timeoutMs, String onTimeout) throws Exception {
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new RuntimeException(onTimeout);
        } catch (ExecutionException ee) {
            Throwable c = ee.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c != null ? c : ee);
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
